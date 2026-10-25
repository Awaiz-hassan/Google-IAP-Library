package com.zyroxlimited.billing.internal

import com.zyroxlimited.billing.AlreadyEntitledReason
import com.zyroxlimited.billing.BillingConfig
import com.zyroxlimited.billing.BillingConnectionState
import com.zyroxlimited.billing.BillingError
import com.zyroxlimited.billing.BillingErrorCode
import com.zyroxlimited.billing.BillingProduct
import com.zyroxlimited.billing.ChangeTiming
import com.zyroxlimited.billing.EntitlementStatus
import com.zyroxlimited.billing.OneTimeOffer
import com.zyroxlimited.billing.ProductType
import com.zyroxlimited.billing.ProductsState
import com.zyroxlimited.billing.PurchaseInfo
import com.zyroxlimited.billing.PurchaseOperation
import com.zyroxlimited.billing.PurchaseOptions
import com.zyroxlimited.billing.PurchaseResult
import com.zyroxlimited.billing.PurchaseState
import com.zyroxlimited.billing.PurchasesRefreshState
import com.zyroxlimited.billing.ReplacementMode
import com.zyroxlimited.billing.ReplacementRequiredReason
import com.zyroxlimited.billing.SubscriptionChangeInfo
import com.zyroxlimited.billing.SubscriptionChangeRequest
import com.zyroxlimited.billing.SubscriptionOffer
import com.zyroxlimited.billing.VerifiedPurchaseState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the billing lifecycle: connection, product catalog, purchase flow, on-device validation, acknowledgement
 * and the entitlement state. One instance per process (created by [com.zyroxlimited.billing.BillingManager]).
 *
 * Concurrency: every change to the purchase snapshot (full refreshes and purchase updates) runs
 * under [entitlementMutex], and purchase updates are consumed in arrival order from [updates], so a slow or
 * out-of-order response can never overwrite newer entitlement data.
 */
internal class BillingEngine(
    val productIds: List<String>,
    private val gateway: BillingGateway,
    private val config: BillingConfig,
    private val store: BillingStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val logger = config.logger
    private val premiumIds = productIds.toSet()
    private val lock = Any()

    // Connection. Guarded by [lock].
    private var connectionWaiter: CompletableDeferred<BillingError?>? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    @Volatile private var closed = false

    // Products.
    private val productsMutex = Mutex()

    @Volatile private var catalog: ProductCatalog? = null

    // Entitlements. Guarded by [entitlementMutex].
    private val entitlementMutex = Mutex()
    private var snapshot: PurchaseSnapshot? = null
    private var hasAuthoritativeSnapshot = false
    private var completedRefreshTicket = 0L
    private var lastRefreshSucceeded = false
    private val processedTokens = HashSet<String>()
    private val pendingTokens = HashSet<String>()
    private val refreshTickets = AtomicLong(0)

    @Volatile private var lastForegroundRefreshAt: Long? = null

    // Acknowledgement.
    private val acknowledgingTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val acknowledgedTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Purchase flow.
    private val activeLaunch = AtomicReference<PendingLaunch?>(null)
    private val updates = Channel<PurchasesUpdate>(Channel.UNLIMITED)

    private class PendingLaunch(val productId: String) {
        val result = CompletableDeferred<PurchaseResult>()

        @Volatile var change: SubscriptionChangeInfo? = null
    }

    private sealed interface Plan {
        data class Done(val result: PurchaseResult) : Plan
        data class Launch(val request: LaunchRequest, val change: SubscriptionChangeInfo?) : Plan
    }

    private val connectionListener = object : GatewayConnectionListener {
        override fun onSetupFinished(error: BillingError?) {
            val waiter = synchronized(lock) { connectionWaiter.also { connectionWaiter = null } }
            if (error == null) {
                synchronized(lock) { reconnectAttempt = 0 }
                store.connectionState.value = BillingConnectionState.Connected
                waiter?.complete(null)
                if (!closed) scope.launch { onConnected() }
            } else {
                logger.warn("Billing setup failed: ${error.message}")
                store.connectionState.value = if (
                    error.code == BillingErrorCode.BILLING_UNAVAILABLE || error.code == BillingErrorCode.FEATURE_NOT_SUPPORTED
                ) {
                    BillingConnectionState.Unavailable(error)
                } else {
                    BillingConnectionState.Disconnected
                }
                waiter?.complete(error)
                store.emitError(error)
                if (error.isRetryable) scheduleReconnect()
            }
        }

        override fun onDisconnected() {
            logger.warn("Billing service disconnected")
            val waiter = synchronized(lock) { connectionWaiter.also { connectionWaiter = null } }
            store.connectionState.value = BillingConnectionState.Disconnected
            waiter?.complete(BillingError(BillingErrorCode.SERVICE_DISCONNECTED, "Billing service disconnected"))
            // The Play result of a running flow may be lost with the connection; the refresh after reconnecting
            // picks up whatever was bought.
            activeLaunch.get()?.let { launch ->
                resolve(
                    launch,
                    PurchaseResult.Failure(
                        launch.productId,
                        BillingError(
                            BillingErrorCode.PURCHASE_STATE_UNKNOWN,
                            "Connection to Play was lost during the purchase; purchases are refreshed after reconnecting",
                        ),
                    ),
                )
            }
            scheduleReconnect()
        }
    }

    fun start() {
        gateway.purchasesListener = PurchasesUpdatedCallback { updates.trySend(it) }
        // The process-start ON_START callback must not trigger a second refresh right after this one.
        lastForegroundRefreshAt = clock()
        scope.launch {
            for (update in updates) {
                try {
                    handlePurchasesUpdate(update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to process a purchase update", e)
                }
            }
        }
        scope.launch { ensureConnected() }
    }

    fun close() {
        closed = true
        activeLaunch.getAndSet(null)?.let { launch ->
            launch.result.complete(
                PurchaseResult.Failure(launch.productId, BillingError(BillingErrorCode.SERVICE_DISCONNECTED, "Billing was shut down")),
            )
        }
        gateway.purchasesListener = null
        updates.close()
        gateway.endConnection()
    }

    // region Connection

    /** Returns `null` once connected, or the error that prevented connecting. Concurrent callers share one attempt. */
    suspend fun ensureConnected(): BillingError? {
        if (closed) return BillingError(BillingErrorCode.SERVICE_DISCONNECTED, "Billing was shut down")
        if (gateway.isReady()) return null
        var startAttempt = false
        val waiter = synchronized(lock) {
            connectionWaiter ?: CompletableDeferred<BillingError?>().also {
                connectionWaiter = it
                startAttempt = true
            }
        }
        if (startAttempt) {
            store.connectionState.value = BillingConnectionState.Connecting
            try {
                gateway.startConnection(connectionListener)
            } catch (e: Exception) {
                connectionListener.onSetupFinished(
                    BillingError(BillingErrorCode.SERVICE_UNAVAILABLE, "Could not start the billing connection", cause = e),
                )
            }
        }
        // Without a bound, a setup callback that never arrives would keep every caller (and whatever waits on their
        // locks) suspended for the lifetime of the process.
        val completed = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { waiter.join() } != null
        if (!completed) onConnectionTimeout(waiter)
        return waiter.await()
    }

    private fun onConnectionTimeout(waiter: CompletableDeferred<BillingError?>) {
        synchronized(lock) { if (connectionWaiter === waiter) connectionWaiter = null }
        val error = BillingError(BillingErrorCode.SERVICE_TIMEOUT, "Play did not finish billing setup within ${CONNECTION_TIMEOUT_MS / 1000}s")
        // Only the first caller to time out reports it; the others share the same result.
        if (waiter.complete(error)) {
            logger.warn(error.message)
            store.connectionState.value = BillingConnectionState.Disconnected
            store.emitError(error)
            scheduleReconnect()
        }
    }

    private suspend fun onConnected() = coroutineScope {
        val products = store.productsState.value
        if (!products.hasLoaded || products.error != null) launch { loadProducts() }
        launch { refreshPurchasesNow() }
    }

    private fun scheduleReconnect() {
        if (closed) return
        synchronized(lock) {
            if (reconnectJob?.isActive == true || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) return
            val backoff = minOf(INITIAL_RECONNECT_DELAY_MS shl reconnectAttempt, MAX_RECONNECT_DELAY_MS)
            reconnectAttempt++
            reconnectJob = scope.launch {
                delay(backoff)
                synchronized(lock) { reconnectJob = null }
                ensureConnected()
            }
        }
    }

    fun onForeground() {
        if (closed) return
        val now = clock()
        val last = lastForegroundRefreshAt
        if (last != null && now - last < config.minForegroundRefreshIntervalMillis) return
        lastForegroundRefreshAt = now
        synchronized(lock) { reconnectAttempt = 0 }
        scope.launch {
            val products = store.productsState.value
            if (!products.hasLoaded || products.error != null) launch { loadProducts() }
            refreshPurchasesNow()
        }
    }

    // endregion

    // region Products

    fun refreshProducts() {
        scope.launch { loadProducts() }
    }

    suspend fun loadProducts(): Boolean = productsMutex.withLock {
        store.productsState.update { it.copy(isLoading = true) }
        ensureConnected()?.let { return@withLock productsFailed(it) }
        val results = coroutineScope {
            listOf(
                async { gateway.queryProducts(productIds, ProductType.SUBSCRIPTION) },
                async { gateway.queryProducts(productIds, ProductType.ONE_TIME) },
            ).map { it.await() }
        }
        val queries = mutableListOf<RawProductQuery>()
        for (result in results) {
            when (result) {
                is GatewayResult.Success -> queries += result.value
                is GatewayResult.Error -> return@withLock productsFailed(result.error)
            }
        }
        val newCatalog = ProductMapper.buildCatalog(productIds, queries, config.offerSelector)
        catalog = newCatalog
        store.products.value = newCatalog.products
        store.productsState.value = ProductsState(
            isLoading = false,
            products = newCatalog.products,
            unavailable = newCatalog.unavailable,
            error = null,
            hasLoaded = true,
        )
        if (newCatalog.unavailable.isNotEmpty()) {
            logger.warn("Products not available from Play: ${newCatalog.unavailable.joinToString { "${it.productId} (${it.reason})" }}")
        }
        true
    }

    private fun productsFailed(error: BillingError): Boolean {
        logger.warn("Product details query failed: ${error.message}")
        store.productsState.update { it.copy(isLoading = false, error = error) }
        return false
    }

    // endregion

    // region Purchases refresh & entitlement

    fun refreshPurchases() {
        scope.launch { refreshPurchasesNow() }
    }

    /**
     * Re-queries all owned purchases and re-validates them. Calls that arrive while a refresh is running are
     * coalesced into the next refresh. Returns false on failure, in which case the entitlement is left unchanged.
     */
    suspend fun refreshPurchasesNow(): Boolean {
        val ticket = refreshTickets.incrementAndGet()
        return entitlementMutex.withLock {
            if (completedRefreshTicket >= ticket) return@withLock lastRefreshSucceeded
            val coveredTicket = refreshTickets.get()
            val succeeded = refreshLocked()
            completedRefreshTicket = coveredTicket
            lastRefreshSucceeded = succeeded
            succeeded
        }
    }

    private suspend fun refreshLocked(): Boolean {
        store.refreshState.update { it.copy(isRefreshing = true) }
        val purchases = when (val owned = queryOwnedPurchases()) {
            is GatewayResult.Success -> owned.value
            is GatewayResult.Error -> return refreshFailed(owned.error)
        }
        val states = purchases.filter { it.state == PurchaseState.PURCHASED }.associate { it.purchaseToken to ClientSideValidator.validate(it) }
        val newSnapshot = PurchaseSnapshot(purchases, states)
        hasAuthoritativeSnapshot = true
        val status = applySnapshotLocked(newSnapshot)
        reportCompletedPendingPurchases(purchases, status)
        // Tokens Play no longer reports (expired, refunded, replaced) are dropped so these sets stay bounded.
        val liveTokens = purchases.mapTo(HashSet()) { it.purchaseToken }
        processedTokens.retainAll(liveTokens)
        acknowledgedTokens.retainAll(liveTokens)
        scheduleAcknowledgements(newSnapshot)
        store.refreshState.value = PurchasesRefreshState(isRefreshing = false, lastError = null, lastSuccessAtMillis = clock())
        return true
    }

    private fun refreshFailed(error: BillingError): Boolean {
        // A failed query proves nothing about ownership: the published entitlement stays as is.
        logger.warn("Purchase refresh failed, keeping the last known entitlement: ${error.message}")
        store.refreshState.update { it.copy(isRefreshing = false, lastError = error) }
        return false
    }

    /** Owned purchases of configured products, from both product types. Fails if either query fails. */
    private suspend fun queryOwnedPurchases(): GatewayResult<List<PurchaseInfo>> {
        ensureConnected()?.let { return GatewayResult.Error(it) }
        val includeSuspended = gateway.isFeatureSupported(GatewayFeature.INCLUDE_SUSPENDED_SUBSCRIPTIONS)
        val results = coroutineScope {
            listOf(
                async { gateway.queryPurchases(ProductType.SUBSCRIPTION, includeSuspended) },
                async { gateway.queryPurchases(ProductType.ONE_TIME, includeSuspended = false) },
            ).map { it.await() }
        }
        val all = mutableListOf<PurchaseInfo>()
        for (result in results) {
            when (result) {
                is GatewayResult.Success -> all += result.value
                is GatewayResult.Error -> return result
            }
        }
        val (relevant, foreign) = all.partition { isPremiumPurchase(it) }
        if (foreign.isNotEmpty()) logger.debug("Ignoring ${foreign.size} purchase(s) of products not passed to initialize()")
        return GatewayResult.Success(relevant)
    }

    /** Must be called under [entitlementMutex]. */
    private fun applySnapshotLocked(newSnapshot: PurchaseSnapshot): EntitlementStatus {
        snapshot = newSnapshot
        val status = EntitlementEvaluator.evaluate(newSnapshot, premiumIds, clock())
        // Before the first full refresh, partial data (a single purchase update) can prove premium but never its absence.
        if (!hasAuthoritativeSnapshot && !status.isPremium) return store.entitlementStatus.value
        if (status != store.entitlementStatus.value) {
            logger.debug("Entitlement: ${if (status.isPremium) "premium" else "not premium"} ($status)")
        }
        store.publishEntitlement(status)
        return status
    }

    /** Purchases that were pending in this process and are now valid get a success event. */
    private fun reportCompletedPendingPurchases(purchases: List<PurchaseInfo>, status: EntitlementStatus) {
        val byToken = purchases.associateBy { it.purchaseToken }
        val iterator = pendingTokens.iterator()
        while (iterator.hasNext()) {
            val purchase = byToken[iterator.next()]
            when {
                purchase == null -> iterator.remove()
                purchase.state == PurchaseState.PURCHASED && status.grants(purchase.purchaseToken) -> {
                    iterator.remove()
                    processedTokens += purchase.purchaseToken
                    store.emitResult(PurchaseResult.Success(premiumProductId(purchase), purchase, subscriptionChange = null))
                }
            }
        }
        purchases.filter { it.state == PurchaseState.PENDING }.mapTo(pendingTokens) { it.purchaseToken }
    }

    private fun scheduleAcknowledgements(source: PurchaseSnapshot) {
        for (purchase in source.purchases) {
            if (purchase.state != PurchaseState.PURCHASED || purchase.isAcknowledged) continue
            val state = source.states[purchase.purchaseToken] ?: continue
            // Only validated purchases are acknowledged; Play refunds unacknowledged purchases after 3 days.
            if (!grants(purchase, state)) continue
            val token = purchase.purchaseToken
            if (token in acknowledgedTokens || !acknowledgingTokens.add(token)) continue
            scope.launch { acknowledge(purchase) }
        }
    }

    private suspend fun acknowledge(purchase: PurchaseInfo) {
        val token = purchase.purchaseToken
        try {
            repeat(ACKNOWLEDGE_ATTEMPTS) { attempt ->
                val error = ensureConnected() ?: when (val result = gateway.acknowledge(token)) {
                    is GatewayResult.Success -> {
                        acknowledgedTokens += token
                        logger.debug("Acknowledged purchase of ${purchase.productIds}")
                        return
                    }
                    is GatewayResult.Error -> result.error
                }
                if (!error.isRetryable || attempt == ACKNOWLEDGE_ATTEMPTS - 1) {
                    // Retried on the next refresh while Play still reports the purchase as unacknowledged.
                    logger.warn("Acknowledgement failed for ${purchase.productIds}: ${error.message}")
                    store.emitError(
                        BillingError(
                            BillingErrorCode.ACKNOWLEDGE_FAILED,
                            "Could not acknowledge ${purchase.productIds}: ${error.message}",
                            playResponseCode = error.playResponseCode,
                            playDebugMessage = error.playDebugMessage,
                        ),
                    )
                    return
                }
                delay(ACKNOWLEDGE_RETRY_DELAY_MS)
            }
        } finally {
            acknowledgingTokens.remove(token)
        }
    }

    // endregion

    // region Purchase flow

    fun launchPurchase(host: LaunchHost, productId: String, options: PurchaseOptions) {
        scope.launch { purchase(host, productId, options) }
    }

    suspend fun purchase(host: LaunchHost, productId: String, options: PurchaseOptions): PurchaseResult {
        val launch = PendingLaunch(productId)
        if (!activeLaunch.compareAndSet(null, launch)) {
            return failure(productId, BillingErrorCode.PURCHASE_IN_PROGRESS, "Another purchase flow is still running")
                .also(store::emitResult)
        }
        store.purchaseOperation.value = PurchaseOperation.Preparing(productId)
        val immediate = try {
            prepareAndLaunch(host, productId, options, launch)
        } catch (e: CancellationException) {
            if (activeLaunch.compareAndSet(launch, null)) store.purchaseOperation.value = PurchaseOperation.Idle
            throw e
        } catch (e: Exception) {
            logger.error("Purchase of $productId failed unexpectedly", e)
            PurchaseResult.Failure(productId, BillingError(BillingErrorCode.UNKNOWN, e.message ?: "Unexpected error", cause = e))
        }
        if (immediate != null) resolve(launch, immediate)
        return launch.result.await()
    }

    /** Returns the outcome if the purchase ends before Play is shown, or `null` once the Play sheet is launched. */
    private suspend fun prepareAndLaunch(
        host: LaunchHost,
        productId: String,
        options: PurchaseOptions,
        launch: PendingLaunch,
    ): PurchaseResult? {
        if (productId !in premiumIds) {
            return failure(productId, BillingErrorCode.PRODUCT_NOT_CONFIGURED, "$productId was not passed to BillingManager.initialize()")
        }
        if (!host.isUsable) return failure(productId, BillingErrorCode.ACTIVITY_INVALID, "The Activity is finishing or destroyed")
        ensureConnected()?.let { return PurchaseResult.Failure(productId, it) }

        val product = catalog?.get(productId) ?: run {
            loadProducts()
            catalog?.get(productId)
        } ?: return failure(
            productId,
            BillingErrorCode.PRODUCT_NOT_AVAILABLE,
            "$productId is not available from Play: " +
                (store.productsState.value.unavailable.firstOrNull { it.productId == productId }?.reason
                    ?: store.productsState.value.error?.message ?: "unknown reason"),
        )

        // Ownership decides between a new purchase and a replacement, so never guess it.
        val owned = currentOwnership() ?: return failure(
            productId,
            BillingErrorCode.OWNERSHIP_UNKNOWN,
            "Owned purchases could not be loaded; not starting a purchase that could duplicate a subscription",
        )

        val plan = when (product.type) {
            ProductType.ONE_TIME -> planOneTime(product, owned, options)
            ProductType.SUBSCRIPTION -> planSubscription(product, owned, options)
        }
        val launchPlan = when (plan) {
            is Plan.Done -> return plan.result
            is Plan.Launch -> plan
        }
        if (!host.isUsable) return failure(productId, BillingErrorCode.ACTIVITY_INVALID, "The Activity is finishing or destroyed")

        launch.change = launchPlan.change
        store.purchaseOperation.value = PurchaseOperation.AwaitingUser(productId)
        logger.debug(
            "Launching purchase of $productId" +
                (launchPlan.change?.let { " replacing ${it.oldProductId} with ${it.replacementMode}" } ?: ""),
        )
        return when (val result = gateway.launchBillingFlow(host, launchPlan.request)) {
            is GatewayResult.Success -> null
            is GatewayResult.Error -> resultForPlayError(productId, result.error, isReplacement = launchPlan.change != null)
        }
    }

    private suspend fun currentOwnership(): List<PurchaseInfo>? = when (val owned = queryOwnedPurchases()) {
        is GatewayResult.Success -> owned.value
        is GatewayResult.Error -> entitlementMutex.withLock { snapshot?.purchases?.takeIf { hasAuthoritativeSnapshot } }
    }

    private fun planOneTime(product: BillingProduct, owned: List<PurchaseInfo>, options: PurchaseOptions): Plan {
        val productId = product.productId
        val sameProduct = owned.filter { it.type == ProductType.ONE_TIME && productId in it.productIds }
        if (sameProduct.any { it.state == PurchaseState.PURCHASED }) {
            refreshPurchases()
            return Plan.Done(PurchaseResult.AlreadyOwned(productId))
        }
        sameProduct.firstOrNull { it.state == PurchaseState.PENDING }?.let {
            return Plan.Done(PurchaseResult.Pending(productId, it, isSubscriptionChange = false))
        }
        if (ownsLifetime(owned)) {
            return Plan.Done(PurchaseResult.AlreadyEntitled(productId, AlreadyEntitledReason.LIFETIME_OWNED))
        }
        val offer = selectOneTimeOffer(product, options)
            ?: return Plan.Done(failure(productId, BillingErrorCode.OFFER_NOT_AVAILABLE, "No matching one-time offer for $productId"))
        return Plan.Launch(launchRequest(productId, offer.offerToken, replacement = null, options), change = null)
    }

    private fun planSubscription(product: BillingProduct, owned: List<PurchaseInfo>, options: PurchaseOptions): Plan {
        val productId = product.productId
        if (ownsLifetime(owned) && !config.allowSubscriptionWhenLifetimeOwned) {
            return Plan.Done(PurchaseResult.AlreadyEntitled(productId, AlreadyEntitledReason.LIFETIME_OWNED))
        }
        val subscriptions = owned.filter { it.type == ProductType.SUBSCRIPTION }
        if (subscriptions.any { it.pendingUpdate != null }) {
            return Plan.Done(
                failure(productId, BillingErrorCode.SUBSCRIPTION_CHANGE_PENDING, "A previous subscription change is still waiting for payment"),
            )
        }
        subscriptions.firstOrNull { it.state == PurchaseState.PENDING }?.let { pending ->
            return Plan.Done(
                if (productId in pending.productIds) {
                    PurchaseResult.Pending(productId, pending, isSubscriptionChange = false)
                } else {
                    failure(productId, BillingErrorCode.PENDING_PURCHASE_EXISTS, "Another subscription purchase is waiting for payment")
                },
            )
        }
        val purchased = subscriptions.filter { it.state == PurchaseState.PURCHASED }
        val active = purchased.filterNot { it.isSuspended }
        val sameProduct = active.firstOrNull { productId in it.productIds }
        if (sameProduct != null && options.basePlanId == null) {
            refreshPurchases()
            return Plan.Done(PurchaseResult.AlreadyOwned(productId))
        }
        val offer = selectSubscriptionOffer(product, options)
            ?: return Plan.Done(failure(productId, BillingErrorCode.OFFER_NOT_AVAILABLE, "No matching subscription offer for $productId"))

        // Explicit base plan of an owned product = plan switch inside that product; otherwise replace the newest one.
        val old = sameProduct ?: active.maxByOrNull { it.purchaseTimeMillis }
        if (old == null) {
            purchased.firstOrNull { it.isSuspended }?.let { suspended ->
                return Plan.Done(
                    PurchaseResult.ReplacementRequired(
                        productId,
                        premiumProductId(suspended),
                        ReplacementRequiredReason.EXISTING_SUBSCRIPTION_SUSPENDED,
                    ),
                )
            }
            return Plan.Launch(launchRequest(productId, offer.offerToken, replacement = null, options), change = null)
        }

        val oldProductId = premiumProductId(old)
        if (!config.subscriptionChangesEnabled) {
            return Plan.Done(
                PurchaseResult.ReplacementRequired(productId, oldProductId, ReplacementRequiredReason.SUBSCRIPTION_CHANGES_DISABLED),
            )
        }
        if (!gateway.isFeatureSupported(GatewayFeature.SUBSCRIPTIONS_UPDATE)) {
            return Plan.Done(
                PurchaseResult.ReplacementRequired(productId, oldProductId, ReplacementRequiredReason.SUBSCRIPTION_UPDATE_NOT_SUPPORTED),
            )
        }
        val mode = options.replacementMode ?: config.replacementPolicy.replacementMode(
            SubscriptionChangeRequest(oldProductId, catalog?.get(oldProductId), product, offer),
        )
        val change = SubscriptionChangeInfo(
            oldProductId = oldProductId,
            newProductId = productId,
            replacementMode = mode,
            timing = if (mode == ReplacementMode.DEFERRED) ChangeTiming.NEXT_RENEWAL else ChangeTiming.IMMEDIATE,
        )
        return Plan.Launch(
            launchRequest(productId, offer.offerToken, Replacement(old.purchaseToken, oldProductId, mode), options),
            change,
        )
    }

    private fun selectSubscriptionOffer(product: BillingProduct, options: PurchaseOptions): SubscriptionOffer? = when {
        options.offerId != null -> product.subscriptionOffers.firstOrNull {
            it.offerId == options.offerId && (options.basePlanId == null || it.basePlanId == options.basePlanId)
        }
        options.basePlanId == null -> product.defaultSubscriptionOffer
        else -> config.offerSelector.select(product, options.basePlanId)
            ?.takeIf { it.basePlanId == options.basePlanId && it in product.subscriptionOffers }
    }

    private fun selectOneTimeOffer(product: BillingProduct, options: PurchaseOptions): OneTimeOffer? =
        if (options.offerId == null && options.purchaseOptionId == null) {
            product.defaultOneTimeOffer
        } else {
            product.oneTimeOffers.firstOrNull {
                (options.offerId == null || it.offerId == options.offerId) &&
                    (options.purchaseOptionId == null || it.purchaseOptionId == options.purchaseOptionId)
            }
        }

    private fun launchRequest(productId: String, offerToken: String?, replacement: Replacement?, options: PurchaseOptions) =
        LaunchRequest(
            productId = productId,
            offerToken = offerToken,
            replacement = replacement,
            obfuscatedAccountId = options.obfuscatedAccountId,
            obfuscatedProfileId = options.obfuscatedProfileId,
        )

    private fun resultForPlayError(productId: String, error: BillingError, isReplacement: Boolean): PurchaseResult =
        when (error.code) {
            BillingErrorCode.USER_CANCELED -> PurchaseResult.Canceled(productId)
            BillingErrorCode.ITEM_ALREADY_OWNED -> {
                refreshPurchases()
                PurchaseResult.AlreadyOwned(productId)
            }
            BillingErrorCode.DEVELOPER_ERROR,
            BillingErrorCode.PLAY_ERROR,
            BillingErrorCode.FEATURE_NOT_SUPPORTED,
            BillingErrorCode.ITEM_UNAVAILABLE,
            BillingErrorCode.ITEM_NOT_OWNED,
            -> PurchaseResult.Failure(
                productId,
                if (isReplacement) error.copy(code = BillingErrorCode.REPLACEMENT_FAILED, message = "Subscription change rejected: ${error.message}") else error,
            )
            else -> PurchaseResult.Failure(productId, error)
        }

    /** Completes [launch] with [result] and emits it; returns false (emitting nothing) if it was already resolved. */
    private fun resolve(launch: PendingLaunch, result: PurchaseResult): Boolean {
        if (!activeLaunch.compareAndSet(launch, null)) return false
        store.purchaseOperation.value = PurchaseOperation.Idle
        launch.result.complete(result)
        store.emitResult(result)
        return true
    }

    private suspend fun handlePurchasesUpdate(update: PurchasesUpdate) {
        val launch = activeLaunch.get()
        when (update) {
            is PurchasesUpdate.Error -> {
                if (launch != null) {
                    resolve(launch, resultForPlayError(launch.productId, update.error, isReplacement = launch.change != null))
                } else {
                    logger.warn("Purchase update error outside a purchase flow: ${update.error.message}")
                }
            }
            is PurchasesUpdate.Purchases -> handlePurchases(update, launch)
        }
    }

    private suspend fun handlePurchases(update: PurchasesUpdate.Purchases, launch: PendingLaunch?) {
        val (relevant, foreign) = update.purchases.partition { isPremiumPurchase(it) }
        if (foreign.isNotEmpty()) logger.warn("Ignoring purchase(s) of products not passed to initialize(): ${foreign.flatMap { it.productIds }}")
        launch?.let { store.purchaseOperation.value = PurchaseOperation.Processing(it.productId) }

        val results = entitlementMutex.withLock { relevant.mapNotNull { processPurchaseLocked(it, launch) } }

        if (launch == null) {
            results.forEach(store::emitResult)
        } else {
            // processPurchaseLocked already maps deferred replacements (reported on the old product) to the launch.
            val forLaunch = results.firstOrNull { it.productId == launch.productId }
            results.filter { it !== forLaunch }.forEach(store::emitResult)
            if (forLaunch != null) {
                // The launch may have been resolved meanwhile (e.g. disconnection); the outcome is still reported.
                if (!resolve(launch, forLaunch)) store.emitResult(forLaunch)
            } else {
                // Play reported success without usable purchase data: read the outcome from a full refresh.
                refreshPurchasesNow()
                resolve(launch, resultFromLatestState(launch))
            }
        }
        if (relevant.isNotEmpty() || update.hasUnresolvedPurchases) refreshPurchases()
    }

    /** Must be called under [entitlementMutex]. Returns `null` for duplicates that were already handled. */
    private fun processPurchaseLocked(purchase: PurchaseInfo, launch: PendingLaunch?): PurchaseResult? {
        val change = launch?.change
        val matchesLaunch = launch != null &&
            (launch.productId in purchase.productIds || (change != null && change.oldProductId in purchase.productIds))
        val productId = if (matchesLaunch) launch.productId else premiumProductId(purchase)
        val token = purchase.purchaseToken
        val base = snapshot ?: PurchaseSnapshot.EMPTY

        if (purchase.pendingUpdate != null) {
            // The old subscription keeps its access until the new one's payment completes.
            applySnapshotLocked(base.upsert(purchase, base.states[token]))
            return PurchaseResult.Pending(productId, purchase, isSubscriptionChange = true)
        }
        when (purchase.state) {
            PurchaseState.PENDING -> {
                pendingTokens += token
                applySnapshotLocked(base.upsert(purchase, state = null))
                return PurchaseResult.Pending(productId, purchase, isSubscriptionChange = false)
            }
            PurchaseState.UNSPECIFIED -> return null
            PurchaseState.PURCHASED -> Unit
        }
        if (token in processedTokens) {
            logger.debug("Duplicate purchase update ignored for ${purchase.productIds}")
            return null
        }
        val state = ClientSideValidator.validate(purchase)
        if (!grants(purchase, state)) {
            // A suspended subscription grants nothing; it is reflected in the entitlement, not as a purchase outcome.
            applySnapshotLocked(base.upsert(purchase, state))
            return null
        }
        processedTokens += token
        pendingTokens -= token
        applySnapshotLocked(base.upsert(purchase, state))
        scheduleAcknowledgements(PurchaseSnapshot(listOf(purchase), mapOf(token to state)))
        return PurchaseResult.Success(productId, purchase, change.takeIf { matchesLaunch })
    }

    private suspend fun resultFromLatestState(launch: PendingLaunch): PurchaseResult {
        val change = launch.change
        // With DEFERRED, Play keeps reporting the old product (with the new token) until the renewal date.
        val candidates = setOfNotNull(launch.productId, change?.takeIf { it.timing == ChangeTiming.NEXT_RENEWAL }?.oldProductId)
        val (purchases, status) = entitlementMutex.withLock { snapshot?.purchases.orEmpty() to store.entitlementStatus.value }
        val matching = purchases.filter { purchase -> purchase.productIds.any { it in candidates } }
        matching.firstOrNull { it.state == PurchaseState.PURCHASED && status.grants(it.purchaseToken) }?.let {
            return PurchaseResult.Success(launch.productId, it, change)
        }
        matching.firstOrNull { it.state == PurchaseState.PENDING || it.pendingUpdate != null }?.let {
            return PurchaseResult.Pending(launch.productId, it, isSubscriptionChange = it.pendingUpdate != null)
        }
        return failure(
            launch.productId,
            BillingErrorCode.PURCHASE_STATE_UNKNOWN,
            "Play finished the purchase without purchase data; the state updates on the next refresh",
        )
    }

    // endregion

    private fun isPremiumPurchase(purchase: PurchaseInfo) = purchase.productIds.any { it in premiumIds }

    private fun premiumProductId(purchase: PurchaseInfo) = purchase.productIds.firstOrNull { it in premiumIds } ?: purchase.productIds.first()

    /** Every configured one-time product is a lifetime unlock; [owned] only contains configured products. */
    private fun ownsLifetime(owned: List<PurchaseInfo>) =
        owned.any { it.type == ProductType.ONE_TIME && it.state == PurchaseState.PURCHASED }

    private fun grants(purchase: PurchaseInfo, state: VerifiedPurchaseState) = when (purchase.type) {
        ProductType.ONE_TIME -> state == VerifiedPurchaseState.ACTIVE
        ProductType.SUBSCRIPTION -> state.grantsAccess && !purchase.isSuspended
    }

    private fun EntitlementStatus.grants(token: String): Boolean {
        val entitlements = (this as? EntitlementStatus.Active)?.entitlements ?: return false
        return entitlements.lifetime?.purchaseToken == token || entitlements.activeSubscriptions.any { it.purchaseToken == token }
    }

    private fun failure(productId: String, code: BillingErrorCode, message: String) =
        PurchaseResult.Failure(productId, BillingError(code, message))

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 30_000L
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L
        const val ACKNOWLEDGE_ATTEMPTS = 2
        const val ACKNOWLEDGE_RETRY_DELAY_MS = 2_000L
    }
}

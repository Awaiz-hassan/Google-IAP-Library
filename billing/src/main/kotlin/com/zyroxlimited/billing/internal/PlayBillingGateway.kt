package com.zyroxlimited.billing.internal

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.zyroxlimited.billing.BillingError
import com.zyroxlimited.billing.BillingErrorCode
import com.zyroxlimited.billing.BillingLogger
import com.zyroxlimited.billing.InstallmentPlan
import com.zyroxlimited.billing.PendingPurchaseUpdate
import com.zyroxlimited.billing.ProductType
import com.zyroxlimited.billing.PurchaseInfo
import com.zyroxlimited.billing.PurchaseState
import com.zyroxlimited.billing.RecurrenceMode
import com.zyroxlimited.billing.ReplacementMode
import com.zyroxlimited.billing.UnavailableProduct
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The only class that touches Play Billing Library types. One BillingClient per process. */
internal class PlayBillingGateway(
    context: Context,
    private val logger: BillingLogger,
) : BillingGateway {

    @Volatile
    override var purchasesListener: PurchasesUpdatedCallback? = null

    /** Latest ProductDetails per ID; Play requires the object itself to launch a purchase. */
    private val productDetails = ConcurrentHashMap<String, ProductDetails>()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                // Pending transactions for prepaid plans and subscription upgrades / downgrades.
                .enablePrepaidPlans()
                .build(),
        )
        // Play re-establishes the connection itself when an API is called while disconnected.
        .enableAutoServiceReconnection()
        .build()

    override fun startConnection(listener: GatewayConnectionListener) {
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    listener.onSetupFinished(
                        if (result.responseCode == BillingResponseCode.OK) null else PlayErrors.from(result, "Billing setup"),
                    )
                }

                override fun onBillingServiceDisconnected() {
                    listener.onDisconnected()
                }
            },
        )
    }

    override fun isReady(): Boolean = client.isReady

    override fun isFeatureSupported(feature: GatewayFeature): Boolean {
        if (!client.isReady) return false
        val playFeature = when (feature) {
            GatewayFeature.SUBSCRIPTIONS_UPDATE -> FeatureType.SUBSCRIPTIONS_UPDATE
            GatewayFeature.INCLUDE_SUSPENDED_SUBSCRIPTIONS -> FeatureType.INCLUDE_SUSPENDED_SUBSCRIPTIONS
        }
        return client.isFeatureSupported(playFeature).responseCode == BillingResponseCode.OK
    }

    override suspend fun queryProducts(productIds: List<String>, type: ProductType): GatewayResult<RawProductQuery> {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(type.toPlay())
                        .build()
                },
            )
            .build()
        return awaitPlay("Product details query") { continuation ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                val value = if (result.responseCode == BillingResponseCode.OK) {
                    val details = queryResult.productDetailsList.orEmpty()
                    details.forEach { productDetails[it.productId] = it }
                    GatewayResult.Success(
                        RawProductQuery(
                            products = details.mapNotNull { it.toRaw() },
                            unavailable = queryResult.unfetchedProductList.orEmpty().map {
                                UnavailableProduct(it.productId, PlayErrors.unavailableReasonOf(it.statusCode))
                            },
                        ),
                    )
                } else {
                    GatewayResult.Error(PlayErrors.from(result, "Product details query").withCode(BillingErrorCode.PRODUCT_DETAILS_FAILED))
                }
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

    override suspend fun queryPurchases(type: ProductType, includeSuspended: Boolean): GatewayResult<List<PurchaseInfo>> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(type.toPlay())
            .apply { if (type == ProductType.SUBSCRIPTION && includeSuspended) includeSuspendedSubscriptions(true) }
            .build()
        return awaitPlay("Purchases query") { continuation ->
            client.queryPurchasesAsync(params) { result, purchases ->
                val value = if (result.responseCode == BillingResponseCode.OK) {
                    GatewayResult.Success(purchases.orEmpty().map { it.toInfo(type) })
                } else {
                    GatewayResult.Error(PlayErrors.from(result, "Purchases query").withCode(BillingErrorCode.PURCHASES_QUERY_FAILED))
                }
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

    override suspend fun acknowledge(purchaseToken: String): GatewayResult<Unit> {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        return awaitPlay("Acknowledgement") { continuation ->
            client.acknowledgePurchase(params) { result ->
                val value = if (result.responseCode == BillingResponseCode.OK) {
                    GatewayResult.Success(Unit)
                } else {
                    GatewayResult.Error(PlayErrors.from(result, "Acknowledgement"))
                }
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

    override suspend fun launchBillingFlow(host: LaunchHost, request: LaunchRequest): GatewayResult<Unit> =
        withContext(Dispatchers.Main.immediate) {
            val activity = (host as? ActivityLaunchHost)?.activity
                ?: return@withContext GatewayResult.Error(
                    BillingError(BillingErrorCode.ACTIVITY_INVALID, "The Activity is finishing or destroyed"),
                )
            val details = productDetails[request.productId]
                ?: return@withContext GatewayResult.Error(
                    BillingError(BillingErrorCode.PRODUCT_NOT_AVAILABLE, "No product details loaded for ${request.productId}"),
                )

            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .apply {
                    request.offerToken?.let { setOfferToken(it) }
                    request.replacement?.let {
                        setSubscriptionProductReplacementParams(
                            SubscriptionProductReplacementParams.newBuilder()
                                .setOldProductId(it.oldProductId)
                                .setReplacementMode(it.mode.toPlay())
                                .build(),
                        )
                    }
                }
                .build()

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .apply {
                    // Product-level replacement params still need the purchase being replaced.
                    request.replacement?.let {
                        setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldPurchaseToken(it.oldPurchaseToken)
                                .build(),
                        )
                    }
                    request.obfuscatedAccountId?.let { setObfuscatedAccountId(it) }
                    request.obfuscatedProfileId?.let { setObfuscatedProfileId(it) }
                }
                .build()

            val result = client.launchBillingFlow(activity, flowParams)
            if (result.responseCode == BillingResponseCode.OK) {
                GatewayResult.Success(Unit)
            } else {
                GatewayResult.Error(PlayErrors.from(result, "Launching the purchase flow"))
            }
        }

    override fun endConnection() {
        client.endConnection()
    }

    /**
     * Suspends until Play invokes the callback. Play normally always answers (with SERVICE_TIMEOUT if needed), but a
     * missing callback would otherwise keep the caller, and everything queued behind its lock, suspended forever.
     */
    private suspend fun <T> awaitPlay(
        operation: String,
        call: (CancellableContinuation<GatewayResult<T>>) -> Unit,
    ): GatewayResult<T> =
        withTimeoutOrNull(PLAY_CALL_TIMEOUT_MS) { suspendCancellableCoroutine(call) }
            ?: GatewayResult.Error(
                BillingError(BillingErrorCode.SERVICE_TIMEOUT, "$operation got no response from Play within ${PLAY_CALL_TIMEOUT_MS / 1000}s"),
            )

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val listener = purchasesListener ?: return
        if (result.responseCode != BillingResponseCode.OK) {
            listener.onPurchasesUpdated(PurchasesUpdate.Error(PlayErrors.from(result, "Purchase")))
            return
        }
        var unresolved = false
        val mapped = purchases.orEmpty().mapNotNull { purchase ->
            // Purchase does not carry its product type; it is known from the queried product details.
            val type = purchase.products.firstNotNullOfOrNull { productDetails[it]?.productType }?.let(::productTypeOf)
            if (type == null) {
                unresolved = true
                null
            } else {
                purchase.toInfo(type)
            }
        }
        if (unresolved) logger.debug("Purchase update for products without loaded details; resolving with a refresh")
        listener.onPurchasesUpdated(PurchasesUpdate.Purchases(mapped, unresolved))
    }

    private fun ProductDetails.toRaw(): RawProduct? {
        val type = productTypeOf(productType) ?: return null
        return RawProduct(
            productId = productId,
            type = type,
            title = title,
            name = name,
            description = description,
            subscriptionOffers = subscriptionOfferDetails.orEmpty().map { offer ->
                RawSubscriptionOffer(
                    basePlanId = offer.basePlanId,
                    offerId = offer.offerId,
                    offerToken = offer.offerToken,
                    offerTags = offer.offerTags.orEmpty(),
                    pricingPhases = offer.pricingPhases.pricingPhaseList.orEmpty().map { phase ->
                        RawPricingPhase(
                            formattedPrice = phase.formattedPrice,
                            priceAmountMicros = phase.priceAmountMicros,
                            priceCurrencyCode = phase.priceCurrencyCode,
                            billingPeriod = phase.billingPeriod,
                            billingCycleCount = phase.billingCycleCount,
                            recurrenceMode = recurrenceModeOf(phase.recurrenceMode),
                        )
                    },
                    installmentPlan = offer.installmentPlanDetails?.let {
                        InstallmentPlan(
                            commitmentPaymentsCount = it.installmentPlanCommitmentPaymentsCount,
                            subsequentCommitmentPaymentsCount = it.subsequentInstallmentPlanCommitmentPaymentsCount,
                        )
                    },
                )
            },
            oneTimeOffers = oneTimeOffers(),
        )
    }

    private fun ProductDetails.oneTimeOffers(): List<RawOneTimeOffer> {
        val default = oneTimePurchaseOfferDetails
        val offers = oneTimePurchaseOfferDetailsList?.takeIf { it.isNotEmpty() } ?: listOfNotNull(default)
        return offers.map { offer ->
            RawOneTimeOffer(
                offerId = offer.offerId,
                purchaseOptionId = offer.purchaseOptionId,
                offerToken = offer.offerToken,
                offerTags = offer.offerTags.orEmpty(),
                formattedPrice = offer.formattedPrice,
                priceAmountMicros = offer.priceAmountMicros,
                priceCurrencyCode = offer.priceCurrencyCode,
                fullPriceMicros = offer.fullPriceMicros,
                isRental = offer.rentalDetails != null,
                isPreorder = offer.preorderDetails != null,
                isPlayDefault = default != null && offer.offerToken == default.offerToken,
            )
        }
    }

    private fun Purchase.toInfo(type: ProductType) = PurchaseInfo(
        purchaseToken = purchaseToken,
        productIds = products,
        type = type,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNSPECIFIED
        },
        orderId = orderId?.takeIf { it.isNotEmpty() },
        purchaseTimeMillis = purchaseTime,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        isSuspended = isSuspended,
        quantity = quantity,
        originalJson = originalJson,
        signature = signature,
        obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId,
        obfuscatedProfileId = accountIdentifiers?.obfuscatedProfileId,
        pendingUpdate = pendingPurchaseUpdate?.let { PendingPurchaseUpdate(it.products, it.purchaseToken) },
    )

    private fun ProductType.toPlay(): String = when (this) {
        ProductType.SUBSCRIPTION -> BillingClient.ProductType.SUBS
        ProductType.ONE_TIME -> BillingClient.ProductType.INAPP
    }

    private fun productTypeOf(playType: String?): ProductType? = when (playType) {
        BillingClient.ProductType.SUBS -> ProductType.SUBSCRIPTION
        BillingClient.ProductType.INAPP -> ProductType.ONE_TIME
        else -> null
    }

    private fun recurrenceModeOf(mode: Int): RecurrenceMode = when (mode) {
        ProductDetails.RecurrenceMode.INFINITE_RECURRING -> RecurrenceMode.INFINITE_RECURRING
        ProductDetails.RecurrenceMode.FINITE_RECURRING -> RecurrenceMode.FINITE_RECURRING
        ProductDetails.RecurrenceMode.NON_RECURRING -> RecurrenceMode.NON_RECURRING
        else -> RecurrenceMode.UNKNOWN
    }

    private fun ReplacementMode.toPlay(): Int = when (this) {
        ReplacementMode.WITH_TIME_PRORATION -> SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION
        ReplacementMode.CHARGE_PRORATED_PRICE -> SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
        ReplacementMode.WITHOUT_PRORATION -> SubscriptionProductReplacementParams.ReplacementMode.WITHOUT_PRORATION
        ReplacementMode.CHARGE_FULL_PRICE -> SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE
        ReplacementMode.DEFERRED -> SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
    }

    private fun BillingError.withCode(code: BillingErrorCode): BillingError =
        // Keep specific connection / availability codes; replace only the generic ones.
        if (this.code == BillingErrorCode.PLAY_ERROR || this.code == BillingErrorCode.UNKNOWN) copy(code = code) else this

    private companion object {
        const val PLAY_CALL_TIMEOUT_MS = 60_000L
    }
}

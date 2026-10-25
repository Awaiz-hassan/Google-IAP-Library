package com.zyroxlimited.billing

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zyroxlimited.billing.internal.ActivityLaunchHost
import com.zyroxlimited.billing.internal.AppForegroundObserver
import com.zyroxlimited.billing.internal.BillingEngine
import com.zyroxlimited.billing.internal.BillingGateway
import com.zyroxlimited.billing.internal.BillingStore
import com.zyroxlimited.billing.internal.PlayBillingGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-scoped entry point of the SDK. Call [initialize] once (e.g. in `Application.onCreate`);
 * every Activity and ViewModel then observes the same state.
 *
 * ```
 * BillingManager.initialize(
 *     context = applicationContext,
 *     productIds = listOf("premium_monthly", "premium_yearly", "premium_lifetime"),
 * )
 * ```
 *
 * All validation happens on the device, from the purchase data reported by the Play Billing client: no backend,
 * no signature check and no Play Integrity API. A modified app or rooted device can bypass on-device checks, and
 * refunds, revocations and expirations are only seen when the Play Store's purchase cache drops the purchase.
 */
public object BillingManager : PremiumBilling {

    private class Runtime(
        val engine: BillingEngine,
        val scope: CoroutineScope,
        val foregroundObserver: AppForegroundObserver?,
    )

    private val store = BillingStore()
    private val lock = Any()

    @Volatile private var runtime: Runtime? = null

    public val isInitialized: Boolean get() = runtime != null

    /**
     * Connects to Google Play, loads [productIds] (subscriptions and one-time products, auto-detected) and restores
     * purchases. Further calls are ignored until [shutdown]. Throws [IllegalArgumentException] for an empty ID list.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        productIds: List<String>,
        config: BillingConfig = BillingConfig(),
    ) {
        val appContext = context.applicationContext
        initializeInternal(
            productIds = productIds,
            config = config,
            gatewayFactory = { PlayBillingGateway(appContext, config.logger) },
            observeForeground = true,
            dispatcher = Dispatchers.Default,
            clock = System::currentTimeMillis,
        )
    }

    /** Seam for tests: same guard and wiring as [initialize], with a fake gateway. Returns false if already initialized. */
    internal fun initializeInternal(
        productIds: List<String>,
        config: BillingConfig,
        gatewayFactory: () -> BillingGateway,
        observeForeground: Boolean,
        dispatcher: CoroutineDispatcher,
        clock: () -> Long,
    ): Boolean = synchronized(lock) {
        val ids = productIds.map(String::trim).filter(String::isNotEmpty).distinct()
        runtime?.let { existing ->
            if (existing.engine.productIds != ids) {
                config.logger.warn("BillingManager.initialize() called again with different product IDs; ignored. Call shutdown() first.")
            }
            return false
        }
        require(ids.isNotEmpty()) { "BillingManager.initialize() needs at least one product ID" }

        val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t -> config.logger.error("Unexpected billing error", t) },
        )
        val engine = BillingEngine(
            productIds = ids,
            gateway = gatewayFactory(),
            config = config,
            store = store,
            scope = scope,
            clock = clock,
        )
        val observer = if (observeForeground && config.refreshOnForeground) AppForegroundObserver(engine::onForeground) else null
        runtime = Runtime(engine, scope, observer)
        engine.start()
        observer?.register()
        true
    }

    /** Ends the Play connection and resets all state, e.g. on user sign-out. [initialize] may be called again afterwards. */
    @JvmStatic
    public fun shutdown() {
        synchronized(lock) {
            val current = runtime ?: return
            runtime = null
            current.foregroundObserver?.unregister()
            current.engine.close()
            current.scope.cancel()
            store.reset()
        }
    }

    override val products: StateFlow<List<BillingProduct>> get() = store.products
    override val productsState: StateFlow<ProductsState> get() = store.productsState
    override val isSubscribed: StateFlow<Boolean> get() = store.isPremium
    override val isPremium: StateFlow<Boolean> get() = store.isPremium
    override val entitlementStatus: StateFlow<EntitlementStatus> get() = store.entitlementStatus
    override val connectionState: StateFlow<BillingConnectionState> get() = store.connectionState
    override val purchasesRefreshState: StateFlow<PurchasesRefreshState> get() = store.refreshState
    override val purchaseOperation: StateFlow<PurchaseOperation> get() = store.purchaseOperation
    override val purchaseResults: SharedFlow<PurchaseResult> get() = store.purchaseResults
    override val errors: SharedFlow<BillingError> get() = store.errors

    override fun launchPurchase(activity: Activity, productId: String, options: PurchaseOptions) {
        val engine = runtime?.engine ?: return store.emitResult(notInitialized(productId))
        engine.launchPurchase(ActivityLaunchHost(activity), productId, options)
    }

    override suspend fun purchase(activity: Activity, productId: String, options: PurchaseOptions): PurchaseResult {
        val engine = runtime?.engine ?: return notInitialized(productId).also(store::emitResult)
        return engine.purchase(ActivityLaunchHost(activity), productId, options)
    }

    override fun refreshPurchases() {
        runtime?.engine?.refreshPurchases()
    }

    override suspend fun refreshPurchasesAndAwait(): Boolean = runtime?.engine?.refreshPurchasesNow() ?: false

    override fun refreshProducts() {
        runtime?.engine?.refreshProducts()
    }

    override fun product(productId: String): BillingProduct? = store.products.value.firstOrNull { it.productId == productId }

    override fun openSubscriptionManagement(activity: Activity, productId: String?): Boolean {
        val uri = Uri.Builder().scheme("https").authority(PLAY_HOST).path(PLAY_SUBSCRIPTIONS_PATH).apply {
            if (productId != null) {
                appendQueryParameter("sku", productId)
                appendQueryParameter("package", activity.packageName)
            }
        }.build()
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun notInitialized(productId: String) = PurchaseResult.Failure(
        productId,
        BillingError(BillingErrorCode.NOT_INITIALIZED, "Call BillingManager.initialize() before purchasing"),
    )

    private const val PLAY_HOST = "play.google.com"
    private const val PLAY_SUBSCRIPTIONS_PATH = "store/account/subscriptions"
}

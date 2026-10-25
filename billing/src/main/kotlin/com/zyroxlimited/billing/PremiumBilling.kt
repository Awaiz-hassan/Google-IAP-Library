package com.zyroxlimited.billing

import android.app.Activity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The SDK's public contract, implemented by [BillingManager].
 *
 * Depend on this interface in ViewModels so they can be tested with a fake instead of the real Play connection.
 */
public interface PremiumBilling {

    /** Purchasable products with localized prices, in configuration order. Unavailable IDs are in [productsState]. */
    public val products: StateFlow<List<BillingProduct>>

    public val productsState: StateFlow<ProductsState>

    /**
     * `true` while the user has ANY valid premium entitlement: an active monthly or yearly subscription, or a
     * lifetime purchase. `false` both before purchases were first loaded ([EntitlementStatus.Unknown]) and after
     * loading finds nothing; use [entitlementStatus] to tell those apart.
     */
    public val isSubscribed: StateFlow<Boolean>

    /** Alias of [isSubscribed]; both come from the same evaluation. */
    public val isPremium: StateFlow<Boolean>

    public val entitlementStatus: StateFlow<EntitlementStatus>

    public val connectionState: StateFlow<BillingConnectionState>

    public val purchasesRefreshState: StateFlow<PurchasesRefreshState>

    public val purchaseOperation: StateFlow<PurchaseOperation>

    /** One event per finished purchase attempt, and for purchases Play reports outside a flow. Not replayed. */
    public val purchaseResults: SharedFlow<PurchaseResult>

    /** Background problems that are not tied to a purchase call (acknowledgement, reconnection...). Not replayed. */
    public val errors: SharedFlow<BillingError>

    /**
     * Starts the purchase of [productId] on [activity] and returns immediately; the outcome arrives on
     * [purchaseResults]. The Activity is only used to show the Play sheet and is not retained.
     */
    public fun launchPurchase(activity: Activity, productId: String, options: PurchaseOptions = PurchaseOptions())

    /** Same as [launchPurchase] but suspends until the outcome. The outcome is also emitted on [purchaseResults]. */
    public suspend fun purchase(activity: Activity, productId: String, options: PurchaseOptions = PurchaseOptions()): PurchaseResult

    /** Re-queries Play for owned purchases, e.g. for a "Restore purchases" button. */
    public fun refreshPurchases()

    /** Like [refreshPurchases] but suspends until done; returns false if the refresh failed (entitlement then unchanged). */
    public suspend fun refreshPurchasesAndAwait(): Boolean

    public fun refreshProducts()

    public fun product(productId: String): BillingProduct?

    /**
     * Opens Google Play's subscription management for [productId] (or the subscriptions list when `null`),
     * where users cancel, fix payment for, or resubscribe. Returns false if no app can open it.
     */
    public fun openSubscriptionManagement(activity: Activity, productId: String? = null): Boolean
}

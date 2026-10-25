package com.zyroxlimited.billing

/**
 * Result of the single entitlement evaluation that drives [PremiumBilling.isSubscribed].
 *
 * [Unknown] means nothing has been validated yet (no access is granted). [Inactive] means purchases were queried
 * and validated successfully and none grants premium. A failed query or a disconnection never moves the state
 * from [Active] to [Inactive]; only a successful evaluation of a complete purchase query can.
 */
public sealed interface EntitlementStatus {
    public val isPremium: Boolean

    public data object Unknown : EntitlementStatus {
        override val isPremium: Boolean get() = false
    }

    public data class Active(val entitlements: PremiumEntitlements) : EntitlementStatus {
        override val isPremium: Boolean get() = true
    }

    public data class Inactive(val entitlements: PremiumEntitlements) : EntitlementStatus {
        override val isPremium: Boolean get() = false
    }
}

/**
 * Subscription and lifetime entitlements, kept separate even though both feed the same premium Boolean:
 * `isPremium = activeSubscriptions.isNotEmpty() || lifetime != null`.
 */
public data class PremiumEntitlements(
    /** Subscriptions that currently grant access (including canceled ones that have not expired yet). */
    val activeSubscriptions: List<SubscriptionEntitlement>,
    /** Lifetime one-time purchase, if owned and valid. */
    val lifetime: LifetimeEntitlement?,
    /** Subscriptions that exist but do not grant access and need user action (account hold, paused). */
    val suspendedSubscriptions: List<SubscriptionEntitlement>,
    /** Purchases waiting for payment. They do not grant access. */
    val pendingPurchases: List<PurchaseInfo>,
    val evaluatedAtMillis: Long,
) {
    public val hasActiveSubscription: Boolean get() = activeSubscriptions.isNotEmpty()
    public val hasLifetime: Boolean get() = lifetime != null
    public val isPremium: Boolean get() = hasActiveSubscription || hasLifetime

    /** The subscription to show in the UI: an auto-renewing one first, then the most recent. */
    public val subscription: SubscriptionEntitlement?
        get() = activeSubscriptions.sortedWith(
            compareByDescending<SubscriptionEntitlement> { it.isAutoRenewing }.thenByDescending { it.purchaseTimeMillis },
        ).firstOrNull()

    /** More than one subscription is active, e.g. one was bought outside the app. The user can cancel one in Play. */
    public val hasMultipleActiveSubscriptions: Boolean get() = activeSubscriptions.size > 1

    /** Lifetime is owned while a subscription still renews; suggest the user cancels the subscription in Play. */
    public val hasRedundantSubscription: Boolean
        get() = hasLifetime && activeSubscriptions.any { it.isAutoRenewing }

    /** A plan change is waiting for payment; the current plan keeps granting access meanwhile. */
    public val pendingSubscriptionChange: PendingSubscriptionChange?
        get() = (activeSubscriptions + suspendedSubscriptions).firstNotNullOfOrNull { it.pendingChange }
}

public data class SubscriptionEntitlement(
    val productId: String,
    val productIds: List<String>,
    val purchaseToken: String,
    val state: VerifiedPurchaseState,
    val isAutoRenewing: Boolean,
    val purchaseTimeMillis: Long,
    val orderId: String?,
    val pendingChange: PendingSubscriptionChange?,
) {
    /** Canceled by the user but still inside the paid period. */
    public val isCanceled: Boolean get() = !isAutoRenewing

    override fun toString(): String =
        "SubscriptionEntitlement(productId=$productId, state=$state, autoRenewing=$isAutoRenewing, " +
            "pendingChange=$pendingChange)"
}

public data class LifetimeEntitlement(
    val productId: String,
    val purchaseToken: String,
    val purchaseTimeMillis: Long,
    val orderId: String?,
) {
    override fun toString(): String = "LifetimeEntitlement(productId=$productId, orderId=$orderId)"
}

public data class PendingSubscriptionChange(
    val newProductIds: List<String>,
    val newPurchaseToken: String,
) {
    override fun toString(): String = "PendingSubscriptionChange(newProductIds=$newProductIds)"
}

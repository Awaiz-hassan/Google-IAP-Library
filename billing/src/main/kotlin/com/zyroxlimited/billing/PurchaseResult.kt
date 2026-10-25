package com.zyroxlimited.billing

/** Optional parameters for a purchase. All of them can be left out for the default offer. */
public data class PurchaseOptions(
    /** Subscription base plan to buy. Defaults to the plan of [BillingProduct.defaultSubscriptionOffer]. */
    val basePlanId: String? = null,
    /** Exact subscription offer or one-time offer to buy. */
    val offerId: String? = null,
    /** One-time product purchase option to buy. */
    val purchaseOptionId: String? = null,
    /** Hashed / obfuscated ID of your user account (max 64 chars). Never pass raw emails or user IDs. */
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    /** Overrides [BillingConfig.replacementPolicy] for this purchase when it replaces an existing subscription. */
    val replacementMode: ReplacementMode? = null,
)

/** Final outcome of one purchase attempt (or of a purchase Play reported outside a flow, e.g. a pending payment completing). */
public sealed interface PurchaseResult {
    public val productId: String

    /** Purchase validated and entitlement granted. Acknowledgement runs in the background. */
    public data class Success(
        override val productId: String,
        val purchase: PurchaseInfo,
        /** Set when this purchase replaced another subscription. */
        val subscriptionChange: SubscriptionChangeInfo?,
    ) : PurchaseResult

    /** Payment, or a subscription change, waits for completion. Existing access is unchanged; no new access is granted. */
    public data class Pending(
        override val productId: String,
        val purchase: PurchaseInfo?,
        val isSubscriptionChange: Boolean,
    ) : PurchaseResult

    public data class Canceled(override val productId: String) : PurchaseResult

    /** The user already owns this exact product. Purchases are refreshed. */
    public data class AlreadyOwned(override val productId: String) : PurchaseResult

    /** Purchase not started because the user already has premium through another product. */
    public data class AlreadyEntitled(
        override val productId: String,
        val reason: AlreadyEntitledReason,
    ) : PurchaseResult

    /**
     * The user has another subscription that has to be changed rather than adding a second one, and the SDK could
     * not do it in-app. Offer [PremiumBilling.openSubscriptionManagement] for [currentProductId].
     */
    public data class ReplacementRequired(
        override val productId: String,
        val currentProductId: String,
        val reason: ReplacementRequiredReason,
    ) : PurchaseResult

    public data class Failure(
        override val productId: String,
        val error: BillingError,
    ) : PurchaseResult
}

public enum class AlreadyEntitledReason {
    /** Lifetime premium is owned; a subscription would add nothing. See [BillingConfig.allowSubscriptionWhenLifetimeOwned]. */
    LIFETIME_OWNED,
}

public enum class ReplacementRequiredReason {
    /** [BillingConfig.subscriptionChangesEnabled] is false. */
    SUBSCRIPTION_CHANGES_DISABLED,

    /** The device's Play Store does not support subscription updates. */
    SUBSCRIPTION_UPDATE_NOT_SUPPORTED,

    /** The existing subscription is on account hold / paused and must be fixed or canceled in Play first. */
    EXISTING_SUBSCRIPTION_SUSPENDED,
}

public data class SubscriptionChangeInfo(
    val oldProductId: String,
    val newProductId: String,
    val replacementMode: ReplacementMode,
    val timing: ChangeTiming,
)

public enum class ChangeTiming {
    /** The new plan applies now. */
    IMMEDIATE,

    /** [ReplacementMode.DEFERRED]: the current plan continues until its renewal date, then the new plan starts. */
    NEXT_RENEWAL,
}

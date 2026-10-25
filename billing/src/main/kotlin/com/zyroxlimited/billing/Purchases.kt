package com.zyroxlimited.billing

/** A purchase as reported by the Play Billing client on this device. */
public data class PurchaseInfo(
    val purchaseToken: String,
    val productIds: List<String>,
    val type: ProductType,
    val state: PurchaseState,
    /** `null` for pending purchases. */
    val orderId: String?,
    val purchaseTimeMillis: Long,
    val isAcknowledged: Boolean,
    /** `false` for one-time products and for subscriptions the user has canceled (they stay valid until expiry). */
    val isAutoRenewing: Boolean,
    /** `true` when the subscription is on account hold or paused. Suspended subscriptions never grant access. */
    val isSuspended: Boolean,
    val quantity: Int,
    /** Raw JSON payload from Play (not checked by the SDK). */
    val originalJson: String,
    val signature: String,
    val obfuscatedAccountId: String?,
    val obfuscatedProfileId: String?,
    /** Present while a subscription change (upgrade / downgrade) for this purchase is still pending payment. */
    val pendingUpdate: PendingPurchaseUpdate?,
) {
    // Tokens, signatures and payloads stay out of logs and crash reports.
    override fun toString(): String =
        "PurchaseInfo(productIds=$productIds, type=$type, state=$state, orderId=$orderId, " +
            "acknowledged=$isAcknowledged, autoRenewing=$isAutoRenewing, suspended=$isSuspended, " +
            "pendingUpdate=${pendingUpdate != null})"
}

public enum class PurchaseState {
    PURCHASED,

    /** Payment not completed yet (e.g. cash payment). Never grants access. */
    PENDING,
    UNSPECIFIED,
}

/** The new subscription a pending plan change will switch to once its payment completes. */
public data class PendingPurchaseUpdate(
    val productIds: List<String>,
    val purchaseToken: String,
) {
    override fun toString(): String = "PendingPurchaseUpdate(productIds=$productIds)"
}

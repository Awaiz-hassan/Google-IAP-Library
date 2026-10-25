package com.zyroxlimited.billing

/**
 * State of an owned purchase as established on the device.
 *
 * The SDK validates purchases on the client only, from the data the Play Billing client reports (purchase state,
 * auto-renewal, suspension). There is no backend, signature check or Play Integrity check, so the device learns about
 * refunds, revocations and expirations when the Play Store's purchase cache drops the purchase (checked on every refresh).
 */
public enum class VerifiedPurchaseState(public val grantsAccess: Boolean) {
    /** Valid and (for subscriptions) auto-renewing. */
    ACTIVE(true),

    /** Subscription canceled by the user but still inside the paid period (Play still reports it). */
    CANCELED(true),

    /** Subscription on account hold or paused. Never grants access; the user must fix it in Play. */
    SUSPENDED(false),
}

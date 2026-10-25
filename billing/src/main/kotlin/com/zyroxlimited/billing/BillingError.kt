package com.zyroxlimited.billing

/**
 * Structured error. [message] is a developer diagnostic in English; map [code] to your own localized UI strings.
 */
public data class BillingError(
    val code: BillingErrorCode,
    val message: String,
    /** Raw `BillingClient.BillingResponseCode`, when the error came from Play. */
    val playResponseCode: Int? = null,
    val playDebugMessage: String? = null,
    val cause: Throwable? = null,
) {
    /** Whether the same operation may succeed if retried later without user or developer action. */
    public val isRetryable: Boolean get() = code in RETRYABLE

    private companion object {
        val RETRYABLE = setOf(
            BillingErrorCode.SERVICE_DISCONNECTED,
            BillingErrorCode.SERVICE_UNAVAILABLE,
            BillingErrorCode.SERVICE_TIMEOUT,
            BillingErrorCode.NETWORK_ERROR,
            BillingErrorCode.PLAY_ERROR,
            BillingErrorCode.PURCHASES_QUERY_FAILED,
            BillingErrorCode.PRODUCT_DETAILS_FAILED,
            BillingErrorCode.ACKNOWLEDGE_FAILED,
            BillingErrorCode.PURCHASE_STATE_UNKNOWN,
        )
    }
}

public enum class BillingErrorCode {
    /** [BillingManager.initialize] was not called. */
    NOT_INITIALIZED,

    /** Play Store missing, too old, blocked, or billing unsupported for this account / country. */
    BILLING_UNAVAILABLE,
    FEATURE_NOT_SUPPORTED,
    SERVICE_DISCONNECTED,
    SERVICE_UNAVAILABLE,
    SERVICE_TIMEOUT,
    NETWORK_ERROR,

    /** Generic Play `ERROR` response. */
    PLAY_ERROR,

    /** Play rejected the request as invalid (`DEVELOPER_ERROR`), e.g. wrong offer token or replacement parameters. */
    DEVELOPER_ERROR,

    /** The product ID was not passed to [BillingManager.initialize]. */
    PRODUCT_NOT_CONFIGURED,

    /** The product is configured but Play did not return it (inactive, wrong ID, not propagated, no eligible offer). */
    PRODUCT_NOT_AVAILABLE,
    PRODUCT_DETAILS_FAILED,

    /** The requested base plan / offer does not exist or the user is not eligible for it. */
    OFFER_NOT_AVAILABLE,
    ITEM_UNAVAILABLE,
    ITEM_ALREADY_OWNED,
    ITEM_NOT_OWNED,
    USER_CANCELED,
    PAYMENT_DECLINED,
    USER_INELIGIBLE,

    /** Another purchase flow is still running. */
    PURCHASE_IN_PROGRESS,

    /** The Activity passed to the purchase call is finishing or destroyed. */
    ACTIVITY_INVALID,
    PURCHASES_QUERY_FAILED,

    /** Current ownership could not be determined, so a purchase was not started (it could create a duplicate subscription). */
    OWNERSHIP_UNKNOWN,

    /** Purchase finished but its final state could not be read yet; it will be picked up by the next refresh. */
    PURCHASE_STATE_UNKNOWN,

    ACKNOWLEDGE_FAILED,

    /** A previous subscription change is still waiting for payment. */
    SUBSCRIPTION_CHANGE_PENDING,

    /** Another purchase is still waiting for payment. */
    PENDING_PURCHASE_EXISTS,

    /** Play rejected the subscription replacement. */
    REPLACEMENT_FAILED,
    UNKNOWN,
}

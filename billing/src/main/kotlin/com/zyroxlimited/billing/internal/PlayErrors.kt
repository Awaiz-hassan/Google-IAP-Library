package com.zyroxlimited.billing.internal

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.UnfetchedProduct
import com.zyroxlimited.billing.BillingError
import com.zyroxlimited.billing.BillingErrorCode
import com.zyroxlimited.billing.UnavailableReason

internal object PlayErrors {

    fun from(result: BillingResult, operation: String): BillingError =
        from(result.responseCode, result.debugMessage, result.onPurchasesUpdatedSubResponseCode, operation)

    fun from(responseCode: Int, debugMessage: String?, subResponseCode: Int, operation: String): BillingError {
        val code = when (subResponseCode) {
            OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS -> BillingErrorCode.PAYMENT_DECLINED
            OnPurchasesUpdatedSubResponseCode.USER_INELIGIBLE -> BillingErrorCode.USER_INELIGIBLE
            else -> codeOf(responseCode)
        }
        val detail = debugMessage?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return BillingError(
            code = code,
            message = "$operation failed with ${code.name} (Play response $responseCode)$detail",
            playResponseCode = responseCode,
            playDebugMessage = debugMessage,
        )
    }

    // SERVICE_TIMEOUT is deprecated but older Play Store versions can still return it.
    @Suppress("DEPRECATION")
    fun codeOf(responseCode: Int): BillingErrorCode = when (responseCode) {
        BillingResponseCode.SERVICE_TIMEOUT -> BillingErrorCode.SERVICE_TIMEOUT
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingErrorCode.FEATURE_NOT_SUPPORTED
        BillingResponseCode.SERVICE_DISCONNECTED -> BillingErrorCode.SERVICE_DISCONNECTED
        BillingResponseCode.USER_CANCELED -> BillingErrorCode.USER_CANCELED
        BillingResponseCode.SERVICE_UNAVAILABLE -> BillingErrorCode.SERVICE_UNAVAILABLE
        BillingResponseCode.BILLING_UNAVAILABLE -> BillingErrorCode.BILLING_UNAVAILABLE
        BillingResponseCode.ITEM_UNAVAILABLE -> BillingErrorCode.ITEM_UNAVAILABLE
        BillingResponseCode.DEVELOPER_ERROR -> BillingErrorCode.DEVELOPER_ERROR
        BillingResponseCode.ERROR -> BillingErrorCode.PLAY_ERROR
        BillingResponseCode.ITEM_ALREADY_OWNED -> BillingErrorCode.ITEM_ALREADY_OWNED
        BillingResponseCode.ITEM_NOT_OWNED -> BillingErrorCode.ITEM_NOT_OWNED
        BillingResponseCode.NETWORK_ERROR -> BillingErrorCode.NETWORK_ERROR
        else -> BillingErrorCode.UNKNOWN
    }

    fun unavailableReasonOf(statusCode: Int): UnavailableReason = when (statusCode) {
        UnfetchedProduct.StatusCode.PRODUCT_NOT_FOUND -> UnavailableReason.NOT_FOUND
        UnfetchedProduct.StatusCode.INVALID_PRODUCT_ID_FORMAT -> UnavailableReason.INVALID_PRODUCT_ID
        UnfetchedProduct.StatusCode.NO_ELIGIBLE_OFFER -> UnavailableReason.NO_ELIGIBLE_OFFER
        else -> UnavailableReason.UNKNOWN
    }
}

package com.zyroxlimited.billing.internal

import com.zyroxlimited.billing.ProductType
import com.zyroxlimited.billing.PurchaseInfo
import com.zyroxlimited.billing.VerifiedPurchaseState

/**
 * Client-only validation of a PURCHASED purchase from the data the Play Billing client reports.
 * Play's local purchase list only contains subscriptions that have not expired yet, so a canceled one is still
 * inside its paid period; refunded or revoked purchases disappear from it.
 */
internal object ClientSideValidator {
    fun validate(purchase: PurchaseInfo): VerifiedPurchaseState = when {
        purchase.type == ProductType.ONE_TIME -> VerifiedPurchaseState.ACTIVE
        purchase.isSuspended -> VerifiedPurchaseState.SUSPENDED
        purchase.isAutoRenewing -> VerifiedPurchaseState.ACTIVE
        else -> VerifiedPurchaseState.CANCELED
    }
}

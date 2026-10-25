package com.zyroxlimited.billing.internal

import com.zyroxlimited.billing.EntitlementStatus
import com.zyroxlimited.billing.LifetimeEntitlement
import com.zyroxlimited.billing.PendingSubscriptionChange
import com.zyroxlimited.billing.PremiumEntitlements
import com.zyroxlimited.billing.ProductType
import com.zyroxlimited.billing.PurchaseInfo
import com.zyroxlimited.billing.PurchaseState
import com.zyroxlimited.billing.SubscriptionEntitlement
import com.zyroxlimited.billing.VerifiedPurchaseState

/** Purchases known to the SDK plus the validation state of each purchase token. */
internal data class PurchaseSnapshot(
    val purchases: List<PurchaseInfo>,
    val states: Map<String, VerifiedPurchaseState>,
) {
    /** Adds or replaces a purchase (keyed by token) without touching the others. */
    fun upsert(purchase: PurchaseInfo, state: VerifiedPurchaseState?): PurchaseSnapshot = PurchaseSnapshot(
        purchases = purchases.filterNot { it.purchaseToken == purchase.purchaseToken } + purchase,
        states = if (state == null) states else states + (purchase.purchaseToken to state),
    )

    companion object {
        val EMPTY = PurchaseSnapshot(emptyList(), emptyMap())
    }
}

/**
 * The single place where premium access is decided:
 * `isPremium = hasValidActiveSubscription || hasValidLifetimePurchase`.
 *
 * A purchase grants access only if it is PURCHASED (never PENDING), concerns a configured product, was validated,
 * and is not suspended. Purchases are de-duplicated by token, so repeated reports of the same purchase can never
 * count twice.
 */
internal object EntitlementEvaluator {

    fun evaluate(
        snapshot: PurchaseSnapshot,
        premiumProductIds: Set<String>,
        nowMillis: Long,
    ): EntitlementStatus {
        val purchases = snapshot.purchases
            .associateBy { it.purchaseToken }
            .values
            .filter { purchase -> purchase.productIds.any { it in premiumProductIds } }

        val active = mutableListOf<SubscriptionEntitlement>()
        val suspended = mutableListOf<SubscriptionEntitlement>()
        val lifetimes = mutableListOf<LifetimeEntitlement>()
        val pending = mutableListOf<PurchaseInfo>()

        for (purchase in purchases) {
            when (purchase.state) {
                PurchaseState.PENDING -> {
                    pending += purchase
                    continue
                }
                PurchaseState.UNSPECIFIED -> continue
                PurchaseState.PURCHASED -> Unit
            }
            val validated = snapshot.states[purchase.purchaseToken] ?: continue
            val productId = purchase.productIds.first { it in premiumProductIds }
            when (purchase.type) {
                ProductType.SUBSCRIPTION -> {
                    val entitlement = SubscriptionEntitlement(
                        productId = productId,
                        productIds = purchase.productIds,
                        purchaseToken = purchase.purchaseToken,
                        state = if (purchase.isSuspended) VerifiedPurchaseState.SUSPENDED else validated,
                        isAutoRenewing = purchase.isAutoRenewing,
                        purchaseTimeMillis = purchase.purchaseTimeMillis,
                        orderId = purchase.orderId,
                        pendingChange = purchase.pendingUpdate?.let {
                            PendingSubscriptionChange(it.productIds, it.purchaseToken)
                        },
                    )
                    when {
                        entitlement.state.grantsAccess -> active += entitlement
                        entitlement.state == VerifiedPurchaseState.SUSPENDED -> suspended += entitlement
                    }
                }
                ProductType.ONE_TIME -> {
                    // Lifetime purchases are valid until Play stops reporting them (refund / revocation).
                    if (validated == VerifiedPurchaseState.ACTIVE) {
                        lifetimes += LifetimeEntitlement(
                            productId = productId,
                            purchaseToken = purchase.purchaseToken,
                            purchaseTimeMillis = purchase.purchaseTimeMillis,
                            orderId = purchase.orderId,
                        )
                    }
                }
            }
        }

        val entitlements = PremiumEntitlements(
            activeSubscriptions = active.sortedBy { it.purchaseTimeMillis },
            lifetime = lifetimes.minByOrNull { it.purchaseTimeMillis },
            suspendedSubscriptions = suspended,
            pendingPurchases = pending,
            evaluatedAtMillis = nowMillis,
        )
        return if (entitlements.isPremium) EntitlementStatus.Active(entitlements) else EntitlementStatus.Inactive(entitlements)
    }
}

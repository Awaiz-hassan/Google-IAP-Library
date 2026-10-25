package com.zyroxlimited.billing

import android.util.Log

/** Optional behaviour switches. The defaults suit a monthly + yearly + lifetime premium setup. */
public data class BillingConfig(
    /** Allow buying a subscription while lifetime premium is owned. Off by default: it would add nothing. */
    val allowSubscriptionWhenLifetimeOwned: Boolean = false,
    /**
     * Change an existing subscription in place (upgrade / downgrade) when another one is bought. When false,
     * such purchases return [PurchaseResult.ReplacementRequired] instead of starting a second subscription.
     */
    val subscriptionChangesEnabled: Boolean = true,
    val replacementPolicy: SubscriptionReplacementPolicy = SubscriptionReplacementPolicy.Default,
    val offerSelector: OfferSelector = OfferSelector.Default,
    /** Refresh purchases whenever the app returns to the foreground. */
    val refreshOnForeground: Boolean = true,
    /** Foreground refreshes closer together than this are skipped. Explicit [PremiumBilling.refreshPurchases] calls are never skipped. */
    val minForegroundRefreshIntervalMillis: Long = 30_000,
    val logger: BillingLogger = BillingLogger.Android,
)

/** Google Play subscription replacement modes (product-level `SubscriptionProductReplacementParams.ReplacementMode`). */
public enum class ReplacementMode {
    /** New plan starts now; remaining time of the old plan is credited. Works for any price direction. */
    WITH_TIME_PRORATION,

    /** New plan starts now, same billing date, the price difference is charged. Only for upgrades to a higher price per time unit. */
    CHARGE_PRORATED_PRICE,

    /** New plan starts now at no extra charge until the next billing date. */
    WITHOUT_PRORATION,

    /** New plan starts now and is charged in full; remaining old time is credited. Required when switching to a prepaid plan. */
    CHARGE_FULL_PRICE,

    /** Old plan continues until renewal, then the new one starts. Typical for downgrades. */
    DEFERRED,
}

/** Everything known about a subscription change when the replacement mode is chosen. */
public data class SubscriptionChangeRequest(
    val oldProductId: String,
    /** `null` if the old product is no longer returned by Play. */
    val oldProduct: BillingProduct?,
    val newProduct: BillingProduct,
    val newOffer: SubscriptionOffer,
)

public fun interface SubscriptionReplacementPolicy {
    public fun replacementMode(change: SubscriptionChangeRequest): ReplacementMode

    public companion object {
        /**
         * - switching to a prepaid plan: [ReplacementMode.CHARGE_FULL_PRICE] (the only mode Play allows);
         * - longer period (monthly → yearly): [ReplacementMode.WITH_TIME_PRORATION], immediate, unused time credited;
         * - shorter period (yearly → monthly): [ReplacementMode.DEFERRED], the paid year is kept;
         * - same period, higher price: [ReplacementMode.CHARGE_PRORATED_PRICE]; lower price: [ReplacementMode.DEFERRED];
         * - anything that cannot be compared: [ReplacementMode.WITH_TIME_PRORATION] (Play's default).
         */
        public val Default: SubscriptionReplacementPolicy = SubscriptionReplacementPolicy { change ->
            val newPhase = change.newOffer.recurringPhase
            val oldPhase = change.oldProduct?.defaultSubscriptionOffer?.recurringPhase
            val newDays = newPhase?.billingPeriod?.approximateDays
            val oldDays = oldPhase?.billingPeriod?.approximateDays
            when {
                change.newOffer.isPrepaid -> ReplacementMode.CHARGE_FULL_PRICE
                // A base plan switch inside the same product: the owned plan is not visible on the client.
                change.oldProductId == change.newProduct.productId -> ReplacementMode.WITH_TIME_PRORATION
                newPhase == null || oldPhase == null || newDays == null || oldDays == null ->
                    ReplacementMode.WITH_TIME_PRORATION
                newDays > oldDays -> ReplacementMode.WITH_TIME_PRORATION
                newDays < oldDays -> ReplacementMode.DEFERRED
                newPhase.priceCurrencyCode != oldPhase.priceCurrencyCode -> ReplacementMode.WITH_TIME_PRORATION
                newPhase.priceAmountMicros > oldPhase.priceAmountMicros -> ReplacementMode.CHARGE_PRORATED_PRICE
                newPhase.priceAmountMicros < oldPhase.priceAmountMicros -> ReplacementMode.DEFERRED
                else -> ReplacementMode.WITH_TIME_PRORATION
            }
        }
    }
}

/** Chooses which subscription offer is bought when the caller does not name one. */
public fun interface OfferSelector {
    /**
     * @param basePlanId requested base plan, or `null` for the product's default (first) base plan.
     * @return an offer from [BillingProduct.subscriptionOffers], or `null` if none fits.
     */
    public fun select(product: BillingProduct, basePlanId: String?): SubscriptionOffer?

    public companion object {
        /**
         * Among the offers of the requested (or first) base plan, picks the one with the lowest price in any phase,
         * e.g. a free trial; Play only returns offers the user is eligible for. Ties prefer the plain base plan.
         */
        public val Default: OfferSelector = OfferSelector { product, basePlanId ->
            val offers = product.subscriptionOffers
            val planId = basePlanId
                ?: offers.firstOrNull { it.isBasePlan }?.basePlanId
                ?: offers.firstOrNull()?.basePlanId
            offers.filter { it.basePlanId == planId }.minWithOrNull(
                compareBy<SubscriptionOffer> { offer -> offer.pricingPhases.minOfOrNull { it.priceAmountMicros } ?: Long.MAX_VALUE }
                    .thenBy { if (it.isBasePlan) 0 else 1 },
            )
        }
    }
}

/** Logging hook. Purchase tokens and payloads are never passed to it. */
public interface BillingLogger {
    public fun debug(message: String)
    public fun warn(message: String, throwable: Throwable? = null)
    public fun error(message: String, throwable: Throwable? = null)

    public companion object {
        private const val TAG = "ZyroxBilling"

        public val Android: BillingLogger = object : BillingLogger {
            override fun debug(message: String) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
            }

            override fun warn(message: String, throwable: Throwable?) {
                Log.w(TAG, message, throwable)
            }

            override fun error(message: String, throwable: Throwable?) {
                Log.e(TAG, message, throwable)
            }
        }

        public val None: BillingLogger = object : BillingLogger {
            override fun debug(message: String): Unit = Unit
            override fun warn(message: String, throwable: Throwable?): Unit = Unit
            override fun error(message: String, throwable: Throwable?): Unit = Unit
        }
    }
}

package com.zyroxlimited.billing

import java.math.BigDecimal

/** Google Play product type of a [BillingProduct]. */
public enum class ProductType {
    /** Auto-renewing or prepaid subscription (Play `subs`). */
    SUBSCRIPTION,

    /** One-time product (Play `inapp`). The SDK treats every configured one-time product as a non-consumable lifetime unlock. */
    ONE_TIME,
}

/**
 * A product as returned by Google Play for the current user, with localized prices.
 *
 * Every value comes from Play's `ProductDetails`; nothing is computed or fabricated beyond unit conversion.
 * A product is only published when it has at least one purchasable offer, so [formattedPrice] is always a real Play price.
 */
public data class BillingProduct(
    val productId: String,
    val type: ProductType,
    /** Play title, which includes the app name in parentheses. */
    val title: String,
    /** Product name without the app name. */
    val name: String,
    val description: String,
    /** All subscription offers (base plans and developer offers) the user is eligible for. Empty for one-time products. */
    val subscriptionOffers: List<SubscriptionOffer>,
    /** All purchase options / offers of a one-time product. Empty for subscriptions. */
    val oneTimeOffers: List<OneTimeOffer>,
    /** Offer that [BillingManager.launchPurchase] uses when no base plan / offer is requested (chosen by [OfferSelector]). */
    val defaultSubscriptionOffer: SubscriptionOffer?,
    /** Offer that is purchased for a one-time product when no offer is requested. */
    val defaultOneTimeOffer: OneTimeOffer?,
) {
    /** Localized price the user pays per [billingPeriod] (subscriptions, after any introductory phases) or once (one-time). */
    public val formattedPrice: String
        get() = defaultSubscriptionOffer?.recurringPhase?.formattedPrice ?: defaultOneTimeOffer?.formattedPrice.orEmpty()

    public val priceAmountMicros: Long?
        get() = defaultSubscriptionOffer?.recurringPhase?.priceAmountMicros ?: defaultOneTimeOffer?.priceAmountMicros

    public val priceAmount: BigDecimal?
        get() = priceAmountMicros?.let { BigDecimal.valueOf(it, MICROS_SCALE) }

    /** ISO 4217 currency code, e.g. "EUR". */
    public val priceCurrencyCode: String?
        get() = defaultSubscriptionOffer?.recurringPhase?.priceCurrencyCode ?: defaultOneTimeOffer?.priceCurrencyCode

    /** Renewal period of the default subscription offer; `null` for one-time products. */
    public val billingPeriod: BillingPeriod?
        get() = defaultSubscriptionOffer?.billingPeriod

    /** Free trial / introductory phases of the default subscription offer, in the order they apply. */
    public val introductoryPhases: List<PricingPhase>
        get() = defaultSubscriptionOffer?.introductoryPhases.orEmpty()

    public val freeTrialPeriod: BillingPeriod?
        get() = defaultSubscriptionOffer?.freeTrialPhase?.billingPeriod

    public val basePlanIds: List<String>
        get() = subscriptionOffers.map { it.basePlanId }.distinct()

    public val isSubscription: Boolean get() = type == ProductType.SUBSCRIPTION

    public fun offersForBasePlan(basePlanId: String): List<SubscriptionOffer> =
        subscriptionOffers.filter { it.basePlanId == basePlanId }
}

/** A subscription base plan (when [offerId] is `null`) or a developer-defined offer on a base plan. */
public data class SubscriptionOffer(
    val basePlanId: String,
    val offerId: String?,
    /** Token Play needs to purchase exactly this offer. */
    val offerToken: String,
    val offerTags: List<String>,
    /** Pricing phases in chronological order; the last one is the base plan's renewal phase. */
    val pricingPhases: List<PricingPhase>,
    val installmentPlan: InstallmentPlan?,
) {
    public val isBasePlan: Boolean get() = offerId == null

    /** Phase that applies after all introductory phases (the regular renewal price). */
    public val recurringPhase: PricingPhase? get() = pricingPhases.lastOrNull()

    public val introductoryPhases: List<PricingPhase>
        get() = if (pricingPhases.size > 1) pricingPhases.dropLast(1) else emptyList()

    public val freeTrialPhase: PricingPhase? get() = introductoryPhases.firstOrNull { it.isFree }

    public val billingPeriod: BillingPeriod? get() = recurringPhase?.billingPeriod

    /** Prepaid plans do not renew automatically. */
    public val isPrepaid: Boolean get() = recurringPhase?.recurrenceMode == RecurrenceMode.NON_RECURRING
}

public data class PricingPhase(
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    /** Parsed [billingPeriodIso]; `null` if Play returned a format the SDK does not understand. */
    val billingPeriod: BillingPeriod?,
    /** ISO 8601 duration exactly as returned by Play, e.g. "P1M". */
    val billingPeriodIso: String,
    /** Number of cycles this phase lasts (0 for infinite recurring phases). */
    val billingCycleCount: Int,
    val recurrenceMode: RecurrenceMode,
) {
    public val isFree: Boolean get() = priceAmountMicros == 0L
    public val priceAmount: BigDecimal get() = BigDecimal.valueOf(priceAmountMicros, MICROS_SCALE)
}

public enum class RecurrenceMode { INFINITE_RECURRING, FINITE_RECURRING, NON_RECURRING, UNKNOWN }

public data class InstallmentPlan(
    val commitmentPaymentsCount: Int,
    val subsequentCommitmentPaymentsCount: Int,
)

/** A purchase option or discount offer of a one-time product. */
public data class OneTimeOffer(
    val offerId: String?,
    val purchaseOptionId: String?,
    val offerToken: String?,
    val offerTags: List<String>,
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    /** Price before discount, when this offer is a discount. */
    val fullPriceMicros: Long?,
    val isRental: Boolean,
    val isPreorder: Boolean,
) {
    public val priceAmount: BigDecimal get() = BigDecimal.valueOf(priceAmountMicros, MICROS_SCALE)
}

/** ISO 8601 billing period such as P1W, P1M, P3M or P1Y. */
public data class BillingPeriod(
    val years: Int,
    val months: Int,
    val weeks: Int,
    val days: Int,
    val iso8601: String,
) {
    /** Rough length in days, for comparing periods (a month counts as 30 days, a year as 365). */
    public val approximateDays: Int get() = years * 365 + months * 30 + weeks * 7 + days

    public companion object {
        private val PATTERN = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?$")

        /** Parses an ISO 8601 date-only duration; returns `null` for empty or unsupported values. */
        public fun parse(iso8601: String): BillingPeriod? {
            val match = PATTERN.matchEntire(iso8601) ?: return null
            val parts = match.groupValues.drop(1)
            if (parts.all { it.isEmpty() }) return null
            val (y, m, w, d) = parts.map { it.toIntOrNull() ?: 0 }
            return BillingPeriod(years = y, months = m, weeks = w, days = d, iso8601 = iso8601)
        }
    }
}

/** A configured product ID that Play did not return as purchasable. */
public data class UnavailableProduct(
    val productId: String,
    val reason: UnavailableReason,
)

public enum class UnavailableReason {
    /** Not found for this app, not active in Play Console, or not yet propagated. */
    NOT_FOUND,
    INVALID_PRODUCT_ID,

    /** The product exists but the user is not eligible for any of its offers. */
    NO_ELIGIBLE_OFFER,
    UNKNOWN,
}

private const val MICROS_SCALE = 6

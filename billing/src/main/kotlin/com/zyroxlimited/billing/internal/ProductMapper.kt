package com.zyroxlimited.billing.internal

import com.zyroxlimited.billing.BillingPeriod
import com.zyroxlimited.billing.BillingProduct
import com.zyroxlimited.billing.OfferSelector
import com.zyroxlimited.billing.OneTimeOffer
import com.zyroxlimited.billing.PricingPhase
import com.zyroxlimited.billing.ProductType
import com.zyroxlimited.billing.SubscriptionOffer
import com.zyroxlimited.billing.UnavailableProduct
import com.zyroxlimited.billing.UnavailableReason

internal class ProductCatalog(
    val products: List<BillingProduct>,
    val unavailable: List<UnavailableProduct>,
) {
    private val byId = products.associateBy { it.productId }

    operator fun get(productId: String): BillingProduct? = byId[productId]
}

internal object ProductMapper {

    /**
     * Merges the subscription and one-time queries (every ID is queried as both types, since Play product IDs are
     * unique across types) into products ordered like [configuredIds]. Products without a purchasable offer are
     * reported as unavailable rather than published without a price.
     */
    fun buildCatalog(
        configuredIds: List<String>,
        queries: List<RawProductQuery>,
        offerSelector: OfferSelector,
    ): ProductCatalog {
        val rawById = queries.flatMap { it.products }.associateBy { it.productId }
        val products = mutableListOf<BillingProduct>()
        val unavailable = mutableListOf<UnavailableProduct>()
        for (id in configuredIds) {
            val raw = rawById[id]
            val product = raw?.let { map(it, offerSelector) }
            when {
                product != null -> products += product
                raw != null -> unavailable += UnavailableProduct(id, UnavailableReason.NO_ELIGIBLE_OFFER)
                else -> unavailable += UnavailableProduct(id, mostSpecificReason(id, queries))
            }
        }
        return ProductCatalog(products, unavailable)
    }

    fun map(raw: RawProduct, offerSelector: OfferSelector): BillingProduct? = when (raw.type) {
        ProductType.SUBSCRIPTION -> {
            val offers = raw.subscriptionOffers.mapNotNull(::mapOffer)
            val product = BillingProduct(
                productId = raw.productId,
                type = ProductType.SUBSCRIPTION,
                title = raw.title,
                name = raw.name,
                description = raw.description,
                subscriptionOffers = offers,
                oneTimeOffers = emptyList(),
                defaultSubscriptionOffer = null,
                defaultOneTimeOffer = null,
            )
            offerSelector.select(product, null)
                ?.takeIf { it in offers }
                ?.let { product.copy(defaultSubscriptionOffer = it) }
        }
        ProductType.ONE_TIME -> {
            val offers = raw.oneTimeOffers.map(::mapOneTimeOffer)
            val default = raw.oneTimeOffers
                .indexOfFirst { it.isPlayDefault && !it.isRental && !it.isPreorder }
                .takeIf { it >= 0 }
                ?.let { offers[it] }
                ?: offers.firstOrNull { !it.isRental && !it.isPreorder }
            default?.let {
                BillingProduct(
                    productId = raw.productId,
                    type = ProductType.ONE_TIME,
                    title = raw.title,
                    name = raw.name,
                    description = raw.description,
                    subscriptionOffers = emptyList(),
                    oneTimeOffers = offers,
                    defaultSubscriptionOffer = null,
                    defaultOneTimeOffer = it,
                )
            }
        }
    }

    /** An offer without pricing phases cannot be shown or bought correctly, so it is dropped. */
    private fun mapOffer(raw: RawSubscriptionOffer): SubscriptionOffer? {
        if (raw.pricingPhases.isEmpty() || raw.offerToken.isBlank()) return null
        return SubscriptionOffer(
            basePlanId = raw.basePlanId,
            offerId = raw.offerId,
            offerToken = raw.offerToken,
            offerTags = raw.offerTags,
            pricingPhases = raw.pricingPhases.map {
                PricingPhase(
                    formattedPrice = it.formattedPrice,
                    priceAmountMicros = it.priceAmountMicros,
                    priceCurrencyCode = it.priceCurrencyCode,
                    billingPeriod = BillingPeriod.parse(it.billingPeriod),
                    billingPeriodIso = it.billingPeriod,
                    billingCycleCount = it.billingCycleCount,
                    recurrenceMode = it.recurrenceMode,
                )
            },
            installmentPlan = raw.installmentPlan,
        )
    }

    private fun mapOneTimeOffer(raw: RawOneTimeOffer) = OneTimeOffer(
        offerId = raw.offerId,
        purchaseOptionId = raw.purchaseOptionId,
        offerToken = raw.offerToken,
        offerTags = raw.offerTags,
        formattedPrice = raw.formattedPrice,
        priceAmountMicros = raw.priceAmountMicros,
        priceCurrencyCode = raw.priceCurrencyCode,
        fullPriceMicros = raw.fullPriceMicros,
        isRental = raw.isRental,
        isPreorder = raw.isPreorder,
    )

    // A one-time ID always comes back NOT_FOUND from the subscription query (and vice versa), so a more specific
    // reason from the other query wins.
    private fun mostSpecificReason(productId: String, queries: List<RawProductQuery>): UnavailableReason {
        val reasons = queries.flatMap { q -> q.unavailable.filter { it.productId == productId }.map { it.reason } }
        return reasons.firstOrNull { it != UnavailableReason.NOT_FOUND && it != UnavailableReason.UNKNOWN }
            ?: reasons.firstOrNull { it == UnavailableReason.NOT_FOUND }
            ?: UnavailableReason.UNKNOWN
    }
}

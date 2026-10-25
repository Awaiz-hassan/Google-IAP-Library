package com.zyroxlimited.billing.internal

import com.zyroxlimited.billing.BillingError
import com.zyroxlimited.billing.PurchaseInfo
import com.zyroxlimited.billing.ProductType
import com.zyroxlimited.billing.RecurrenceMode
import com.zyroxlimited.billing.ReplacementMode
import com.zyroxlimited.billing.InstallmentPlan
import com.zyroxlimited.billing.UnavailableProduct

/**
 * Thin seam around Play's BillingClient. Everything above it works on plain Kotlin types,
 * so the purchase and entitlement logic is unit-tested with a fake instead of the Play Store.
 */
internal interface BillingGateway {
    /** Receives every purchase update Play reports, from any flow. Set once, before [startConnection]. */
    var purchasesListener: PurchasesUpdatedCallback?

    fun startConnection(listener: GatewayConnectionListener)

    fun isReady(): Boolean

    /** Only meaningful while connected; `false` when unknown. */
    fun isFeatureSupported(feature: GatewayFeature): Boolean

    suspend fun queryProducts(productIds: List<String>, type: ProductType): GatewayResult<RawProductQuery>

    suspend fun queryPurchases(type: ProductType, includeSuspended: Boolean): GatewayResult<List<PurchaseInfo>>

    suspend fun acknowledge(purchaseToken: String): GatewayResult<Unit>

    /** Shows the Play purchase sheet. Success only means the sheet was launched; the outcome comes via [purchasesListener]. */
    suspend fun launchBillingFlow(host: LaunchHost, request: LaunchRequest): GatewayResult<Unit>

    fun endConnection()
}

internal fun interface PurchasesUpdatedCallback {
    fun onPurchasesUpdated(update: PurchasesUpdate)
}

internal sealed interface PurchasesUpdate {
    /**
     * @param hasUnresolvedPurchases Play reported purchases whose product type could not be determined
     * (product details not loaded yet); they are picked up by a full refresh instead.
     */
    data class Purchases(val purchases: List<PurchaseInfo>, val hasUnresolvedPurchases: Boolean) : PurchasesUpdate
    data class Error(val error: BillingError) : PurchasesUpdate
}

internal interface GatewayConnectionListener {
    /** `null` on success. May be called again later, e.g. after an automatic reconnection. */
    fun onSetupFinished(error: BillingError?)
    fun onDisconnected()
}

internal enum class GatewayFeature { SUBSCRIPTIONS_UPDATE, INCLUDE_SUSPENDED_SUBSCRIPTIONS }

internal sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>
    data class Error(val error: BillingError) : GatewayResult<Nothing>
}

/** Where the Play sheet is shown. Wraps an Activity weakly in production. */
internal interface LaunchHost {
    val isUsable: Boolean
}

internal data class LaunchRequest(
    val productId: String,
    val offerToken: String?,
    val replacement: Replacement?,
    val obfuscatedAccountId: String?,
    val obfuscatedProfileId: String?,
)

internal data class Replacement(
    val oldPurchaseToken: String,
    val oldProductId: String,
    val mode: ReplacementMode,
)

internal data class RawProductQuery(
    val products: List<RawProduct>,
    val unavailable: List<UnavailableProduct>,
)

/** Fields copied 1:1 from Play's ProductDetails. */
internal data class RawProduct(
    val productId: String,
    val type: ProductType,
    val title: String,
    val name: String,
    val description: String,
    val subscriptionOffers: List<RawSubscriptionOffer>,
    val oneTimeOffers: List<RawOneTimeOffer>,
)

internal data class RawSubscriptionOffer(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val offerTags: List<String>,
    val pricingPhases: List<RawPricingPhase>,
    val installmentPlan: InstallmentPlan?,
)

internal data class RawPricingPhase(
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrenceMode: RecurrenceMode,
)

internal data class RawOneTimeOffer(
    val offerId: String?,
    val purchaseOptionId: String?,
    val offerToken: String?,
    val offerTags: List<String>,
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    val fullPriceMicros: Long?,
    val isRental: Boolean,
    val isPreorder: Boolean,
    /** Play's `getOneTimePurchaseOfferDetails()` (the default purchase option) returned this offer. */
    val isPlayDefault: Boolean,
)

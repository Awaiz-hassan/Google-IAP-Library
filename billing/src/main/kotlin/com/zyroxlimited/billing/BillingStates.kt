package com.zyroxlimited.billing

/** Connection to the Play Billing service. Independent of the premium state. */
public sealed interface BillingConnectionState {
    public data object Disconnected : BillingConnectionState
    public data object Connecting : BillingConnectionState
    public data object Connected : BillingConnectionState

    /** Billing cannot work on this device / account right now (e.g. no Play Store). Retried on foreground. */
    public data class Unavailable(val error: BillingError) : BillingConnectionState
}

public data class ProductsState(
    val isLoading: Boolean = false,
    /** Products in the order their IDs were passed to [BillingManager.initialize]. */
    val products: List<BillingProduct> = emptyList(),
    val unavailable: List<UnavailableProduct> = emptyList(),
    /** Last load error. Previously loaded [products] are kept when a reload fails. */
    val error: BillingError? = null,
    val hasLoaded: Boolean = false,
)

public data class PurchasesRefreshState(
    val isRefreshing: Boolean = false,
    /** Error of the last refresh, cleared by the next successful one. Never changes the entitlement by itself. */
    val lastError: BillingError? = null,
    val lastSuccessAtMillis: Long? = null,
)

/** Progress of the current purchase flow, for disabling buttons or showing progress. */
public sealed interface PurchaseOperation {
    public data object Idle : PurchaseOperation

    /** Checking ownership and offers before showing Play. */
    public data class Preparing(val productId: String) : PurchaseOperation

    /** The Google Play purchase sheet is shown. */
    public data class AwaitingUser(val productId: String) : PurchaseOperation

    /** Validating the purchase returned by Play. */
    public data class Processing(val productId: String) : PurchaseOperation
}

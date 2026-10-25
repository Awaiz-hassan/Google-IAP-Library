package com.zyroxlimited.billing.internal

import com.zyroxlimited.billing.BillingConnectionState
import com.zyroxlimited.billing.BillingError
import com.zyroxlimited.billing.BillingProduct
import com.zyroxlimited.billing.EntitlementStatus
import com.zyroxlimited.billing.ProductsState
import com.zyroxlimited.billing.PurchaseOperation
import com.zyroxlimited.billing.PurchaseResult
import com.zyroxlimited.billing.PurchasesRefreshState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All observable state. It exists before initialization so collectors can subscribe at any time, and it
 * outlives engine restarts. [isPremium] is written only together with [entitlementStatus].
 */
internal class BillingStore {
    val products = MutableStateFlow<List<BillingProduct>>(emptyList())
    val productsState = MutableStateFlow(ProductsState())
    private val mutableEntitlement = MutableStateFlow<EntitlementStatus>(EntitlementStatus.Unknown)
    private val mutableIsPremium = MutableStateFlow(false)
    val connectionState = MutableStateFlow<BillingConnectionState>(BillingConnectionState.Disconnected)
    val refreshState = MutableStateFlow(PurchasesRefreshState())
    val purchaseOperation = MutableStateFlow<PurchaseOperation>(PurchaseOperation.Idle)

    private val mutablePurchaseResults = MutableSharedFlow<PurchaseResult>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableErrors = MutableSharedFlow<BillingError>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val entitlementStatus = mutableEntitlement.asStateFlow()
    val isPremium = mutableIsPremium.asStateFlow()
    val purchaseResults = mutablePurchaseResults.asSharedFlow()
    val errors = mutableErrors.asSharedFlow()

    fun publishEntitlement(status: EntitlementStatus) {
        mutableEntitlement.value = status
        mutableIsPremium.value = status.isPremium
    }

    fun emitResult(result: PurchaseResult) {
        mutablePurchaseResults.tryEmit(result)
    }

    fun emitError(error: BillingError) {
        mutableErrors.tryEmit(error)
    }

    /** Back to the pre-initialization state (used by [com.zyroxlimited.billing.BillingManager.shutdown]). */
    fun reset() {
        products.value = emptyList()
        productsState.value = ProductsState()
        publishEntitlement(EntitlementStatus.Unknown)
        connectionState.value = BillingConnectionState.Disconnected
        refreshState.value = PurchasesRefreshState()
        purchaseOperation.value = PurchaseOperation.Idle
    }

    private companion object {
        const val EVENT_BUFFER = 16
    }
}

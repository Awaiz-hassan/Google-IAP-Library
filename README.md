# Google IAP Library

[![JitPack](https://jitpack.io/v/Awaiz-hassan/Google-IAP-Library.svg)](https://jitpack.io/#Awaiz-hassan/Google-IAP-Library)

A Kotlin wrapper around **Google Play Billing Library 9.1** for apps that sell premium access as a monthly subscription, a yearly subscription and/or a lifetime purchase.

You pass in your product IDs, launch purchases with an `Activity`, and observe a single `StateFlow<Boolean>` that is `true` whenever the user owns **any** valid premium product. Connection handling, reconnection, purchase updates, acknowledgement, restore and subscription upgrades / downgrades are handled inside the library.

## Features

- One premium flag: `isSubscribed` is `true` for an active monthly **or** yearly subscription **or** a lifetime purchase.
- Products with localized prices, billing periods, free trials, intro phases and offer tokens, exposed as a `StateFlow`.
- Subscription and one-time product types are detected automatically from the IDs.
- At most one subscription: buying monthly while subscribed to yearly (or the reverse) changes the plan in place through Play's replacement flow instead of creating a second subscription.
- Lifetime and subscription entitlements are tracked separately; lifetime keeps premium after a subscription expires.
- Automatic acknowledgement, duplicate-callback protection, pending purchases, reconnection with backoff and refresh when the app returns to the foreground.
- Structured purchase results and error codes instead of a bare Boolean.
- Loading, connection and purchase-progress states are exposed separately from the premium flag.
- `PremiumBilling` interface so ViewModels can be tested with a fake.

## Requirements

| | |
|---|---|
| Min SDK | 26 |
| Compile SDK of your app | 34 or higher |
| Language | Kotlin (coroutines / `StateFlow` based API) |
| Play Billing Library | 9.1.0 (included as a dependency) |

## Installation

Add the JitPack repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the dependency in your app module:

```kotlin
dependencies {
    implementation("com.github.Awaiz-hassan:Google-IAP-Library:v1.0.0")
}
```

The `com.android.vending.BILLING` permission is merged in automatically.

## Quick start

### 1. Initialize once

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingManager.initialize(
            context = this,
            productIds = listOf("premium_monthly", "premium_yearly", "premium_lifetime"),
        )
    }
}
```

`initialize` connects to Google Play, loads the products and restores existing purchases. Calling it again is ignored; Activities never need to set up billing themselves.

### 2. Observe premium state

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        BillingManager.isSubscribed.collect { isPremium ->
            premiumBadge.isVisible = isPremium
        }
    }
}
```

`isSubscribed` (alias: `isPremium`) is `false` until purchases have been loaded. To tell "not loaded yet" apart from "loaded, not premium", use `entitlementStatus`:

```kotlin
when (val status = BillingManager.entitlementStatus.value) {
    EntitlementStatus.Unknown -> showLoading()
    is EntitlementStatus.Active -> showPremium(status.entitlements)
    is EntitlementStatus.Inactive -> showPaywall()
}
```

### 3. Show products and prices

```kotlin
BillingManager.products.collect { products ->
    products.forEach { product ->
        // e.g. "€4.99" every P1M, "€39.99" every P1Y, "€99.99" once
        println("${product.productId}: ${product.formattedPrice} ${product.billingPeriod?.iso8601 ?: "one-time"}")
        product.freeTrialPeriod?.let { println("Free trial: ${it.iso8601}") }
    }
}
```

Products Play does not return (wrong ID, inactive, no eligible offer) are listed in `productsState.value.unavailable` instead of appearing with a made-up price.

### 4. Purchase

```kotlin
BillingManager.launchPurchase(activity, "premium_yearly")
```

The result arrives on `purchaseResults`. Or suspend until it is done:

```kotlin
val result = BillingManager.purchase(activity, "premium_lifetime")
```

Collect results in a ViewModel so they survive rotation:

```kotlin
BillingManager.purchaseResults.collect { result ->
    when (result) {
        is PurchaseResult.Success -> showThankYou()
        is PurchaseResult.Pending -> showPaymentPending()
        is PurchaseResult.Canceled -> Unit
        is PurchaseResult.AlreadyOwned -> showAlreadyOwned()
        is PurchaseResult.AlreadyEntitled -> showAlreadyPremium()   // e.g. lifetime owned, subscription not needed
        is PurchaseResult.ReplacementRequired -> BillingManager.openSubscriptionManagement(activity, result.currentProductId)
        is PurchaseResult.Failure -> showError(result.error.code)
    }
}
```

### 5. Restore purchases

Purchases are restored automatically on start, on reconnection and when the app returns to the foreground. For a "Restore purchases" button:

```kotlin
val succeeded = BillingManager.refreshPurchasesAndAwait()
```

A failed refresh never removes premium access; it only sets `purchasesRefreshState.value.lastError`.

## ViewModel and Compose example

```kotlin
class PaywallViewModel(
    private val billing: PremiumBilling = BillingManager,
) : ViewModel() {
    val products = billing.products
    val isPremium = billing.isSubscribed
    val operation = billing.purchaseOperation

    private val _messages = MutableSharedFlow<PurchaseResult>()
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch { billing.purchaseResults.collect { _messages.emit(it) } }
    }

    fun buy(activity: Activity, productId: String) = billing.launchPurchase(activity, productId)
    fun restore() = billing.refreshPurchases()
}

@Composable
fun PaywallScreen(viewModel: PaywallViewModel = viewModel()) {
    val activity = LocalActivity.current ?: return
    val products by viewModel.products.collectAsStateWithLifecycle()
    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val busy = operation != PurchaseOperation.Idle

    Column(Modifier.padding(16.dp)) {
        Text(if (isPremium) "Premium active" else "Go premium")
        products.forEach { product ->
            Button(
                onClick = { viewModel.buy(activity, product.productId) },
                enabled = !isPremium && !busy,
            ) {
                Text("${product.name} – ${product.formattedPrice}")
            }
        }
        TextButton(onClick = viewModel::restore) { Text("Restore purchases") }
    }
}
```

`LocalActivity` comes from `androidx.activity:activity-compose`, `collectAsStateWithLifecycle` from `androidx.lifecycle:lifecycle-runtime-compose`.

## How premium is decided

```
isSubscribed = hasActiveSubscription || hasLifetime
```

| Situation | `isSubscribed` |
|---|---|
| Active monthly or yearly subscription | `true` |
| Subscription canceled by the user, paid period not over yet | `true` |
| Subscription expired (Play no longer reports it) | `false` |
| Subscription on account hold or paused | `false` (listed in `suspendedSubscriptions`) |
| Lifetime purchase owned | `true` |
| Lifetime refunded or revoked (Play no longer reports it) | `false` after the next refresh |
| Subscription + lifetime | `true`; still `true` after the subscription expires |
| Pending payment | `false` until the payment completes |
| Purchase canceled or failed | unchanged |
| Billing disconnected, query failed, offline | unchanged (last known state is kept) |
| Purchases not loaded yet | `false`, with `entitlementStatus = Unknown` |

`entitlementStatus` exposes the details in `PremiumEntitlements`: `activeSubscriptions`, `lifetime`, `suspendedSubscriptions`, `pendingPurchases`, `pendingSubscriptionChange`, `hasMultipleActiveSubscriptions` and `hasRedundantSubscription` (lifetime owned while a subscription still renews).

## Subscription changes

If the user already has a subscription and buys the other one, the library replaces it with Google Play's subscription replacement parameters (`SubscriptionProductReplacementParams` with the old purchase token) instead of starting a second subscription.

| Change | Default replacement mode | Effect |
|---|---|---|
| Monthly → yearly (longer period) | `WITH_TIME_PRORATION` | Switches now; unused monthly time is credited |
| Yearly → monthly (shorter period) | `DEFERRED` | Yearly continues until its renewal date, then monthly starts |
| Same period, higher price | `CHARGE_PRORATED_PRICE` | Switches now; the price difference is charged |
| Same period, lower price | `DEFERRED` | Switches at the next renewal |
| To a prepaid plan | `CHARGE_FULL_PRICE` | The only mode Play allows |

Override per purchase with `PurchaseOptions(replacementMode = ...)` or globally with `BillingConfig(replacementPolicy = ...)`. `PurchaseResult.Success.subscriptionChange` tells you whether the change applies now or at the next renewal; the entitlement always reflects what Play reports, never an assumed new plan.

A purchase is **not** started, and a result explains why, when:

- the user already owns the requested subscription → `AlreadyOwned`
- a previous plan change is still waiting for payment → `Failure(SUBSCRIPTION_CHANGE_PENDING)`
- the current subscription is on hold or paused → `ReplacementRequired(EXISTING_SUBSCRIPTION_SUSPENDED)`
- the Play Store does not support subscription updates, or `subscriptionChangesEnabled = false` → `ReplacementRequired`
- owned purchases could not be loaded → `Failure(OWNERSHIP_UNKNOWN)`, so a second subscription is never created by accident

The library enforces this for purchases made through the app. Google Play itself can still let a user buy a second subscription product elsewhere (for example from the Play Store website); `hasMultipleActiveSubscriptions` tells you when that happened, and `openSubscriptionManagement()` sends the user to cancel one.

## Lifetime purchase

The lifetime product is a one-time product that is never consumed.

- If lifetime is already owned, buying it again returns `AlreadyOwned` without opening Play.
- Buying lifetime while subscribed is allowed and keeps `isSubscribed = true`. The subscription keeps renewing until the user cancels it; `hasRedundantSubscription` lets you suggest that.
- While lifetime is owned, subscription purchases return `AlreadyEntitled(LIFETIME_OWNED)`. Allow them with `BillingConfig(allowSubscriptionWhenLifetimeOwned = true)`.

## Purchase options

```kotlin
BillingManager.launchPurchase(
    activity,
    "premium_yearly",
    PurchaseOptions(
        basePlanId = "yearly",            // a specific base plan
        offerId = "intro-50",             // a specific offer on that base plan
        obfuscatedAccountId = hashedUserId,
    ),
)
```

Without options the default offer is used: among the offers Play returns for the first base plan (Play only returns offers the user is eligible for), the one with the lowest price in any phase, such as a free trial. Replace the rule with `BillingConfig(offerSelector = ...)`. For one-time products, `offerId` / `purchaseOptionId` select a specific purchase option.

## Observable state

| Property | Type | Purpose |
|---|---|---|
| `isSubscribed` / `isPremium` | `StateFlow<Boolean>` | Any valid premium entitlement |
| `entitlementStatus` | `StateFlow<EntitlementStatus>` | `Unknown`, `Active(entitlements)`, `Inactive(entitlements)` |
| `products` | `StateFlow<List<BillingProduct>>` | Purchasable products in configuration order |
| `productsState` | `StateFlow<ProductsState>` | `isLoading`, `products`, `unavailable`, `error` |
| `connectionState` | `StateFlow<BillingConnectionState>` | `Disconnected`, `Connecting`, `Connected`, `Unavailable(error)` |
| `purchasesRefreshState` | `StateFlow<PurchasesRefreshState>` | `isRefreshing`, `lastError`, `lastSuccessAtMillis` |
| `purchaseOperation` | `StateFlow<PurchaseOperation>` | `Idle`, `Preparing`, `AwaitingUser`, `Processing` |
| `purchaseResults` | `SharedFlow<PurchaseResult>` | One event per finished purchase attempt |
| `errors` | `SharedFlow<BillingError>` | Background problems, e.g. acknowledgement failures |

| Function | |
|---|---|
| `launchPurchase(activity, productId, options)` | Start a purchase; result on `purchaseResults` |
| `purchase(activity, productId, options)` | Suspending version returning the `PurchaseResult` |
| `refreshPurchases()` / `refreshPurchasesAndAwait()` | Re-query owned purchases (restore) |
| `refreshProducts()` | Reload product details |
| `product(productId)` | Current `BillingProduct`, if loaded |
| `openSubscriptionManagement(activity, productId)` | Open Google Play's subscription screen |
| `shutdown()` | Close the connection and reset state, e.g. on sign-out; `initialize` can be called again |

## Configuration

```kotlin
BillingManager.initialize(
    context = this,
    productIds = listOf("premium_monthly", "premium_yearly", "premium_lifetime"),
    config = BillingConfig(
        allowSubscriptionWhenLifetimeOwned = false,
        subscriptionChangesEnabled = true,
        replacementPolicy = SubscriptionReplacementPolicy.Default,
        offerSelector = OfferSelector.Default,
        refreshOnForeground = true,
        minForegroundRefreshIntervalMillis = 30_000,
        logger = BillingLogger.Android,   // or BillingLogger.None
    ),
)
```

Purchase tokens, signatures and payloads are never written to the log.

## Errors

`PurchaseResult.Failure` and `errors` carry a `BillingError` with a `code`, a developer `message` (English, not meant for the UI), the raw Play response code and `isRetryable`. Map `code` to your own localized strings. Common codes:

| Code | Meaning |
|---|---|
| `BILLING_UNAVAILABLE` | No Play Store, outdated Play Store, or billing unsupported for the account / country |
| `NETWORK_ERROR`, `SERVICE_UNAVAILABLE`, `SERVICE_DISCONNECTED` | Temporary; retry later |
| `PRODUCT_NOT_CONFIGURED` | The ID was not passed to `initialize` |
| `PRODUCT_NOT_AVAILABLE` | Play did not return the product |
| `OFFER_NOT_AVAILABLE` | The requested base plan / offer does not exist or the user is not eligible |
| `PURCHASE_IN_PROGRESS` | Another purchase flow is still open |
| `ACTIVITY_INVALID` | The Activity was finishing or destroyed |
| `PAYMENT_DECLINED`, `USER_INELIGIBLE` | Reported by Play for the purchase |
| `OWNERSHIP_UNKNOWN` | Owned purchases could not be loaded, so no purchase was started |
| `SUBSCRIPTION_CHANGE_PENDING`, `PENDING_PURCHASE_EXISTS` | A previous purchase or plan change is waiting for payment |
| `REPLACEMENT_FAILED` | Play rejected the subscription change |
| `ACKNOWLEDGE_FAILED` | Acknowledgement failed; retried on the next refresh |

## Google Play Console setup

1. Upload a build that includes the library to a testing track (internal testing is enough). Products can only be created after the app has the billing permission.
2. **Monetize → Products → Subscriptions**: create `premium_monthly` and `premium_yearly`, each with an active base plan (P1M and P1Y). Optional offers such as free trials are picked up automatically.
3. **Monetize → Products → One-time products**: create `premium_lifetime` and activate it.
4. **Settings → License testing**: add the Google accounts of your testers so they can buy with test cards without being charged.
5. Install the app from the testing track's opt-in link with a tester account, or run a build with the same application ID on a device signed in with a tester account.

Things worth testing with test cards: purchase of each product, monthly ↔ yearly changes, cancellation (still premium until expiry), the "slow test card" for pending purchases, and refunds from the Play Console order list (premium is removed on the next refresh). Test subscriptions renew every few minutes, so expiration can be observed quickly.

## Client-side only

All validation happens on the device, using the purchase data the Play Billing client reports. There is no server and no signature or Play Integrity check. This keeps integration simple, with these limits:

- Refunds, revocations and expirations are seen when the Play Store's local purchase cache drops the purchase, which the library checks on start, on reconnection, on foreground and on `refreshPurchases()`. There are no real-time server notifications.
- A modified app or rooted device can bypass any on-device check, so premium state should not protect high-value server-side resources.
- Entitlements are per Google account on the device; they are not linked to your own user accounts across platforms.

## Releasing a new version

JitPack builds the library from git tags:

```bash
git tag v1.0.1
git push origin v1.0.1
```

Then open [jitpack.io/#Awaiz-hassan/Google-IAP-Library](https://jitpack.io/#Awaiz-hassan/Google-IAP-Library) and click **Get it** next to the tag to trigger the first build. Apps use `com.github.Awaiz-hassan:Google-IAP-Library:v1.0.1`.

## License

Licensed under the [Apache License 2.0](LICENSE).

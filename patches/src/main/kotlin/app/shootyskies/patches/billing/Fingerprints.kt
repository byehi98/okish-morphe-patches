package app.shootyskies.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Shooty Skies — Google Play Billing 8.0.0: com.android.billingclient.api.
// BillingClientImpl (DEX classes12).
//
// The game ships Unity IAP's C# GooglePlay store and talks straight to the
// Play Billing classes through JNI (no app-owned Java bridge — verified: no
// `BillingClientImpl;->` caller exists outside classes12). Public API names
// (queryPurchasesAsync, launchBillingFlow, …) are Google's — never app-
// obfuscated. The `zz*` filter names are Google's own R8-minified internals:
// stable for the pinned 8.0.0 library, but version-coupled — if a future
// build bumps billing, re-verify them (they are the LAST filters so the
// public name/params still pin the match first).
//
// All six bodies verified in this build's smali (line numbers below):
// registers, filter order and exception tables all read before writing.
// Only launchBillingFlow carries a .catch table (26 directives) — it needs
// the helper-method pattern; the other five are straight-line safe.
// ============================================================================

/**
 * BillingClientImpl.queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener)V
 * — owned-items query (restore / startup entitlement grant).
 *
 * Confirmed smali (BillingClientImpl.smali:10625, `.registers 9`, public
 * final, NO catch table). Body reads `params.zza()` (the queried product
 * type), then builds the zzaw/zzat callables and posts them through zzG —
 * filter order matches exactly: zza → zzaw.<init> → zzat.<init>.
 *
 * Replacing the body with a fabricated owned list is what grants
 * `remove_ads_iap` on every launch (real Play returns [] for a repackaged
 * build, so the SKU would otherwise never appear owned).
 */
object QueryPurchasesAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryPurchasesAsync",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryPurchasesParams;",
        "Lcom/android/billingclient/api/PurchasesResponseListener;",
    ),
    filters = listOf(
        // instruction order (verified): QueryPurchasesParams.zza -> zzaw.<init> -> zzat.<init>
        methodCall(
            definingClass = "Lcom/android/billingclient/api/QueryPurchasesParams;",
            name = "zza",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzaw;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzat;",
            name = "<init>",
        ),
    )
)

/**
 * BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams, AcknowledgePurchaseResponseListener)V
 * — acknowledges durable purchases (remove_ads_iap).
 *
 * Confirmed smali (BillingClientImpl.smali:7199, `.registers 9`, public, NO
 * catch table). Builds the zzaa/zzab callables → zzan handler → zzF executor →
 * zzG future. Filter order (verified): zzaa.<init> → zzab.<init> → zzG.
 *
 * A real service would reject our fabricated purchaseToken, so the fake must
 * never reach it: answer OK to the listener directly instead.
 */
object AcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "acknowledgePurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzaa;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzab;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
            name = "zzG",
        ),
    )
)

/**
 * BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V
 * — consumes consumables (coin packs).
 *
 * Confirmed smali (BillingClientImpl.smali:7248, `.registers 9`, public, NO
 * catch table). Builds zzak/zzam callables → zzG. Filter order (verified):
 * zzak.<init> → zzam.<init>.
 *
 * Proven failure mode elsewhere (ITD2): a fake token hitting the real
 * consumeAsync returns Play error 5 → the grant is dropped. Never let the
 * real path run — answer OK with the original token instead.
 */
object ConsumeAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "consumeAsync",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzak;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzam;",
            name = "<init>",
        ),
    )
)

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * — the buy button: opens the real Google Play payment sheet.
 *
 * Confirmed smali (BillingClientImpl.smali:8485, `.registers 33`, public) —
 * `Random.nextLong()` client ref id → `iget zzf:Lcom/android/billingclient/api/zzs;`
 * → `zzs.zzd()` → PurchasesUpdatedListener null-check → connectivity check →
 * logging → return. Filter order (verified): Random.<init> → zzs.zzd.
 *
 * HAS A CATCH TABLE (26 `.catch`/`.catchall` directives, 136 labels) — a
 * labeled body injected at its entry gets truncated by the instruction-tree
 * writer (BurritoBison/Missiles regression). The patch therefore adds a
 * fresh helper method (no exception table) and injects a straight-line,
 * label-free delegate at the entry.
 */
object LaunchBillingFlowFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Lcom/android/billingclient/api/BillingFlowParams;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/Random;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzs;",
            name = "zzd",
        ),
    )
)

/**
 * BillingClientImpl.queryProductDetailsAsync(QueryProductDetailsParams, ProductDetailsResponseListener)V
 * — fetches the price catalog so the store renders.
 *
 * Confirmed smali (BillingClientImpl.smali:10562, `.registers 9`, public, NO
 * catch table). Builds zzal/zzap callables → zzG; its own failure path shows
 * the QueryProductDetailsResult fabrication shape. Filter order (verified):
 * zzal.<init> → zzap.<init>.
 *
 * Needed because C# reads prices via getOneTimePurchaseOfferDetails()
 * → getFormattedPrice(): fabricating ProductDetails entries (per queried
 * product id) keeps buy buttons enabled even when Play rejects the
 * repackaged signature with DEVELOPER_ERROR / SERVICE_UNAVAILABLE.
 */
object QueryProductDetailsAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryProductDetailsAsync",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryProductDetailsParams;",
        "Lcom/android/billingclient/api/ProductDetailsResponseListener;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzal;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzap;",
            name = "<init>",
        ),
    )
)

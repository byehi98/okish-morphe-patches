package app.shootyskies.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Shooty Skies — Google Play Billing 9.0.0: com.android.billingclient.api.
// BillingClientImpl.
//
// The game ships Unity IAP's C# GooglePlay store and talks straight to the
// Play Billing classes through JNI (no app-owned Java bridge). Public API
// names (queryPurchasesAsync, launchBillingFlow, …) are Google's — never app-
// obfuscated. The `zz*` filter names are Google's own R8-minified internals:
// re-verified for the pinned 9.0.0 library in this build (Billing 9.0.0
// renumbered every lambda class vs 8.0.0 — zzaw/zzat→zzbm/zzae,
// zzaa/zzab→zzal/zzan, zzak/zzam→zzbc/zzbd, zzal/zzap→zzbh/zzbi, and the
// getter holder zzs→zzab). Version-coupled by design — they are the LAST
// filters so the public name/params still pin the match first.
//
// All bodies verified in this build's smali: registers, filter order and
// exception tables read before writing. Only launchBillingFlow carries a
// .catch table — it needs the helper-method pattern; the other four are
// straight-line safe.
// ============================================================================

/**
 * BillingClientImpl.queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener)V
 * — owned-items query (restore / startup entitlement grant).
 *
 * Confirmed smali (BillingClientImpl.smali:11777, `.registers 10`, public
 * final, NO catch table). Body reads `params.zza()` (the queried product
 * type) + `getIncludeSuspendedSubscriptions()`, then builds the
 * zzbm/zzae callables and posts them through zzN — filter order matches
 * exactly: zza → getIncludeSuspendedSubscriptions → zzbm.<init> → zzae.<init>.
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
        // instruction order (verified): QueryPurchasesParams.zza ->
        // getIncludeSuspendedSubscriptions -> zzbm.<init> -> zzae.<init> -> zzN
        methodCall(
            definingClass = "Lcom/android/billingclient/api/QueryPurchasesParams;",
            name = "zza",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/QueryPurchasesParams;",
            name = "getIncludeSuspendedSubscriptions",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzbm;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzae;",
            name = "<init>",
        ),
    )
)

/**
 * BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams, AcknowledgePurchaseResponseListener)V
 * — acknowledges durable purchases (remove_ads_iap).
 *
 * Confirmed smali (BillingClientImpl.smali:8716, `.registers 9`, public, NO
 * catch table). Builds the zzal/zzan callables → BillingClientImpl.zzN.
 * Filter order (verified): zzal.<init> → zzan.<init> → zzN.
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
            definingClass = "Lcom/android/billingclient/api/zzal;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzan;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
            name = "zzN",
        ),
    )
)

/**
 * BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V
 * — consumes consumables (coin packs).
 *
 * Confirmed smali (BillingClientImpl.smali:8765, `.registers 9`, public, NO
 * catch table). Builds zzbc/zzbd callables → BillingClientImpl.zzN.
 * Filter order (verified): zzbc.<init> → zzbd.<init>.
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
            definingClass = "Lcom/android/billingclient/api/zzbc;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzbd;",
            name = "<init>",
        ),
    )
)

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * — the buy button: opens the real Google Play payment sheet.
 *
 * Confirmed smali (BillingClientImpl.smali:10061, `.registers 26`, public) —
 * `Random.nextLong()` client ref id → `iget zzf:Lcom/android/billingclient/api/zzab;`
 * → `zzab.zzf()` (getter) → PurchasesUpdatedListener null-check → connectivity
 * check → logging → return. Filter order (verified): Random.<init> →
 * Random.nextLong → zzab.zzf.
 *
 * HAS A CATCH TABLE (large .catch/.catchall set) — a labeled body injected at
 * its entry gets truncated by the instruction-tree writer (BurritoBison/
 * Missiles regression). The patch therefore adds a fresh helper method (no
 * exception table) and injects a straight-line, label-free delegate at the
 * entry.
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
            definingClass = "Ljava/util/Random;",
            name = "nextLong",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzab;",
            name = "zzf",
        ),
    )
)

/**
 * BillingClientImpl.queryProductDetailsAsync(QueryProductDetailsParams, ProductDetailsResponseListener)V
 * — fetches the price catalog so the store renders.
 *
 * Confirmed smali (BillingClientImpl.smali:11714, `.registers 9`, public, NO
 * catch table). Builds zzbh/zzbi callables → BillingClientImpl.zzN; its own
 * failure path shows the QueryProductDetailsResult fabrication shape. Filter
 * order (verified): zzbh.<init> → zzbi.<init>.
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
            definingClass = "Lcom/android/billingclient/api/zzbh;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzbi;",
            name = "<init>",
        ),
    )
)

package app.crashofcars.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 9.0.0 — Unity IAP purchase flow (classes.dex) ──
//
// All targets are public Google Play Billing API methods on BillingClientImpl.
// These names are never obfuscated and each occurs exactly once in the class
// (verified), so no body filters are needed.
//
// Smali verified against Crash of Cars 1.9.34 (versionCode 1090034, Unity
// 6000.3.22f1 IL2CPP):
//   analysis/com.notdoppler.crashofcars/smali/classes/com/android/billingclient/
//   api/BillingClientImpl.smali   (13 084 lines, `.source billing@@9.0.0`)
//
//   L8744   .method public acknowledgePurchase(...)        .registers 9
//   L8793   .method public consumeAsync(...)               .registers 9
//   L10089  .method public launchBillingFlow(...)           .registers 26  ← has exception table
//   L11744  .method public queryProductDetailsAsync(...)    .registers 9
//   L11807  .method public final queryPurchasesAsync(...)   .registers 10  ← public FINAL
//
// NONE of the five is `synchronized` / `declared-synchronized` (checked on the
// raw `.method` lines), so index-0 injection is correct and no monitor-enter /
// monitor-exit fixup is required. `accessFlags` matching is exact, so an
// over-specified flag set (e.g. adding FINAL where the method is not final)
// silently resolves to zero matches — hence the asymmetry below.

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * — smali `:10089`, `.registers 26` (locals `v0..v22`, `p0=v23`, `p1=v24`,
 * `p2=v25`).
 *
 * PRIMARY hook — fabricate a valid `Purchase` for the tapped SKU and deliver it
 * synchronously through `this.zzf → zzab.zzf()` → `onPurchasesUpdated(OK, [p])`,
 * then return OK so the game never waits on a Play sheet.
 *
 * **Billing 9.0.0 delta:** the listener holder field is
 * `zzf:Lcom/android/billingclient/api/zzab;` and the `PurchasesUpdatedListener`
 * comes from **`zzab.zzf()`** (verified `zzab.smali:132`). The 8.0.0 template's
 * `zzs;->zzd()` is doubly wrong here: `zzs` does not exist, and `zzab.zzd()`
 * returns a `DeveloperProvidedBillingListener` (`zzab.smali:124`) — using it
 * throws `ClassCastException` at the `invoke-interface`. The real body null-checks
 * `zzf` and then `zzf()` at `:10103-10113`; the helper mirrors both checks.
 *
 * Note: PUBLIC only — not final, and not synchronized. The method carries an
 * exception table (`:try_start_47` + `.catchall`, `:try_start_31f`
 * NameNotFound, `:try_start_398` Timeout), so the real body lives in an injected
 * `morpheFakePurchase` helper and only a 4-instruction shim is added at index 0.
 */
object BillingClientImplLaunchBillingFlowFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf(
        "Landroid/app/Activity;",
        "Lcom/android/billingclient/api/BillingFlowParams;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams,
 * AcknowledgePurchaseResponseListener)V — smali `:8744`, `.registers 9`
 * (locals `v0..v5`, `p0=v6`, `p1=v7`, `p2=v8`). Label-free straight-line body,
 * so the OK callback is injected at index 0 directly.
 *
 * Non-consumables finish their Play lifecycle here; without this the game's
 * acknowledge call hangs forever and the grant never lands.
 */
object BillingClientImplAcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "acknowledgePurchase",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V —
 * smali `:8793`, `.registers 9` (locals `v0..v5`, `p0=v6`, `p1=v7`, `p2=v8`).
 * Label-free straight-line body.
 *
 * Consumable coin/gem packs finish here. Must echo
 * `ConsumeParams.getPurchaseToken()` (`ConsumeParams.smali:57`) back through
 * `onConsumeResponse` — UPM treats a null/empty token as a failed consume.
 */
object BillingClientImplConsumeAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "consumeAsync",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * BillingClientImpl.queryProductDetailsAsync(QueryProductDetailsParams,
 * ProductDetailsResponseListener)V — smali `:11744`, `.registers 9`
 * (locals `v0..v5`, `p0=v6`, `p1=v7`, `p2=v8`).
 *
 * Fake `ProductDetails` catalogue so the C# store populates offline instead of
 * showing an empty/greyed sheet. SKU-agnostic — every queried productId is
 * echoed back with a stub `$1.99` price.
 *
 * **Billing 9.0.0 delta:** `QueryProductDetailsParams.zza()` returns
 * `Lcom/google/android/gms/internal/play_billing/zzca;` (`QueryProductDetailsParams.smali:48`),
 * **not** the 8.0.0 `zzbt`. The patch therefore iterates
 * `Collection.iterator()` rather than indexing with `List.get(I)`/`size()` —
 * see the patch file for the full reasoning. The body is injected with
 * `addInstructionsWithLabels`; the method has one label (`:cond_34`) but **no
 * exception table**, so adding labels is safe.
 */
object BillingClientImplQueryProductDetailsAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryProductDetailsAsync",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryProductDetailsParams;",
        "Lcom/android/billingclient/api/ProductDetailsResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * BillingClientImpl.queryPurchasesAsync(QueryPurchasesParams,
 * PurchasesResponseListener)V — smali `:11807`, `.registers 10`
 * (locals `v0..v6`, **`p0=v7`, `p1=v8`, `p2=v9`**).
 *
 * Fake owned-purchases list so entitlements survive an app restart: UPM re-queries
 * owned purchases at startup and re-grants without a tap.
 *
 * ⚠️ Two things differ from the 8.0.0 template: the method is declared
 * **`.method public final`**, so `accessFlags` must be
 * `listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)`; and the register file is
 * **10**, not 9 — the fake-owned-purchases body is budgeted against `v0..v6` with
 * `p2` (v9) as the `PurchasesResponseListener`.
 *
 * Body is label-free straight-line, injected directly at index 0.
 */
object BillingClientImplQueryPurchasesAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryPurchasesAsync",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryPurchasesParams;",
        "Lcom/android/billingclient/api/PurchasesResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)
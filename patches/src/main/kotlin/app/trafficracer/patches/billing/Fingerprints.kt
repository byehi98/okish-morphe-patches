package app.trafficracer.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 8.0.0 — Unity IAP 5.0.3 purchase flow (classes.dex) ─
//
// All targets are public Google Play Billing API methods on BillingClientImpl —
// NEVER obfuscated and unique by (name, returnType, parameters) within the class,
// so no body filters are required. Smali verified against Traffic Racer 4.0
// (versionCode 404): analysis/com.skgames.trafficracer/smali/classes/com/android/
// billingclient/api/BillingClientImpl.smali (source tag `billing@@8.0.0`):
//
//   L11616  .method public launchBillingFlow(...)        .registers 33  public
//   L9849   .method public acknowledgePurchase(...)      .registers 9    public
//   L9917   .method public consumeAsync(...)             .registers 9    public
//   L14534  .method public queryProductDetailsAsync(...) .registers 9    public
//   L14622  .method public final queryPurchasesAsync(...) .registers 9   public final  ← NEW vs Traffic Rider
//
// Each name occurs exactly once in the class (checked). Ported from the
// production-verified trafficrider billing fingerprints — byte-identical
// signatures in both apps (same billing lib version).

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * (classes.dex line 11616, .registers 33 — p0=v30, p1=v31, p2=v32).
 * PRIMARY hook — fabricate a valid Purchase for the tapped SKU and deliver it
 * synchronously via this.zzf → zzs.zzd() → onPurchasesUpdated(OK, [purchase]).
 * Note: PUBLIC only (not final). This method carries an exception table, so the
 * fake-purchase body lives in an injected `morpheFakePurchase` helper and only
 * a 4-instruction shim is added at index 0.
 */
object BillingClientImplLaunchBillingFlowFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf("Landroid/app/Activity;", "Lcom/android/billingclient/api/BillingFlowParams;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * BillingClientImpl.queryProductDetailsAsync(QueryProductDetailsParams,
 * ProductDetailsResponseListener)V (classes.dex line 14534, .registers 9).
 * Fake ProductDetails catalog so the C# store populates — echoes back each
 * queried productId with a stub price, SKU-agnostic.
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
 * BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams,
 * AcknowledgePurchaseResponseListener)V (classes.dex line 9849, .registers 9).
 * Fire OK without a real Play service — non-consumables finish here.
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
 * BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V
 * (classes.dex line 9917, .registers 9). Fire OK with token — consumable
 * cash packs finish here.
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
 * BillingClientImpl.queryPurchasesAsync(QueryPurchasesParams,
 * PurchasesResponseListener)V (classes.dex line 14622, .registers 9).
 * NEW vs Traffic Rider: fake owned-purchases list so entitlements survive
 * restart (CheckAndRestoreReceipts / GooglePlayCheckEntitlementUseCase run
 * this locally) and are granted at startup.
 *
 * ⚠️ accessFlags must be listOf(PUBLIC, FINAL) — the method is declared
 * `.method public final queryPurchasesAsync(...)` (Traffic Rider's copy is
 * public-only, this app's is not).
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

package app.crossyroad.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 8.0.0 (source tag billing@@8.0.0) — Crossy Road 7.13.0 ─
//
// All targets are public Play Billing API methods on the SDK's own
// BillingClientImpl — never obfuscated, unique by (name, returnType,
// parameters) within the class. Smali verified in
// analysis/crossyroad/smali/classes/com/android/billingclient/api/
// BillingClientImpl.smali.
//
// The zzce testing-wrapper subclass (classes4/zzce.smali) routes every one of
// these calls through static synthetic bridges (zzal/zzam/zzan/zzao) that
// invoke-super into the BillingClientImpl methods — so patching the four
// methods below covers BOTH the direct and the wrapper path.

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * (smali line 8476, .registers 33 → p0=v30, p1=v31, p2=v32; large try/catch
 * table — see patch WHY-helper note).
 *
 * PRIMARY hook: forge an OK BillingResult + a fake Purchase for the tapped
 * SKU and deliver it through the registered PurchasesUpdatedListener
 * (this.zzf → zzs.zzd(), smali 8492-8501) so C# grants the product
 * synchronously and no Google Play sheet ever opens.
 *
 * Body filters pin the billing-8.0.0 SKU-extraction sequence, in exact smali
 * order: listener zzd (8499) → BillingFlowParams.zzj (8559) → zzk (8564) →
 * SkuDetails.getSku (8587) → ProductDetails.getProductId (8604).
 */
object LaunchBillingFlowFingerprint : Fingerprint(
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf(
        "Landroid/app/Activity;",
        "Lcom/android/billingclient/api/BillingFlowParams;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzs;",
            name = "zzd"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "zzj"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "zzk"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/SkuDetails;",
            name = "getSku"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/ProductDetails;",
            name = "getProductId"
        )
    )
)

/**
 * BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams,
 * AcknowledgePurchaseResponseListener)V (smali line 7190, .registers 9).
 * C# acknowledges durables right after the grant; the real service would
 * reject the fake token — fire the listener with OK instead.
 */
object AcknowledgePurchaseFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "acknowledgePurchase"
)

/**
 * BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V
 * (smali line 7239, .registers 9). C# consumes coin packs immediately after
 * the grant — same Play-rejection failure mode as acknowledge; report OK
 * with the token from the params.
 */
object ConsumeAsyncFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "consumeAsync"
)

/**
 * BillingClientImpl.queryProductDetailsAsync(QueryProductDetailsParams,
 * ProductDetailsResponseListener)V (smali line 10530, .registers 9, method
 * ends 10591 with NO catch entries — direct labeled injection is safe).
 *
 * Serve a fake per-SKU ProductDetails catalog so the store populates and the
 * buy buttons enable even without a working Play catalog fetch; SKU ids and
 * types are read dynamically from p1.zza() → zzbt of QueryProductDetailsParams$Product
 * (zza()=id, zzb()=type).
 */
object QueryProductDetailsAsyncFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryProductDetailsParams;",
        "Lcom/android/billingclient/api/ProductDetailsResponseListener;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryProductDetailsAsync"
)

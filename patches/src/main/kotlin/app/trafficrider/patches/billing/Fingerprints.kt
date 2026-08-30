package app.trafficrider.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 8.0.0 — Unity IAP 5.0.4 purchase flow (classes.dex) ─
//
// All targets are public Google Play Billing API methods on BillingClientImpl —
// NEVER obfuscated and unique by (name, returnType, parameters) within the class,
// so no body filters are required. Smali verified: classes/com/android/billingclient/
// api/BillingClientImpl.smali (source tag `billing@@8.0.0`).
// Verified identical in Traffic Rider 2.11 (902) vs Missiles 1.41 — same billing lib.
// zz* risk: launchBillingFlow reads SKU via BillingFlowParams.zzk()/zzj() and
// BillingFlowParams$ProductDetailsParams.zza() — R8-minified names stable for 8.0.0.

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * (classes.dex, .registers 33 — p0=v30, p1=v31, p2=v32).
 * PRIMARY hook — fabricate valid Purchase for tapped SKU and deliver synchronously
 * via this.zzf → zzs.zzd() to C# grant.
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
 * ProductDetailsResponseListener)V (.registers 9).
 * Fake ProductDetails catalog so C# store populates.
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
 * AcknowledgePurchaseResponseListener)V (.registers 9).
 * Fire OK without real Play service.
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
 * (.registers 9). Fire OK with token.
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

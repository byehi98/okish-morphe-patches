package app.missiles.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 8.0.0 — Unity IAP 4.14.2 purchase flow (classes.dex) ─
//
// All targets are public Google Play Billing API methods on BillingClientImpl —
// NEVER obfuscated and unique by (name, returnType, parameters) within the class,
// so no body filters are required. Smali verified: classes/com/android/billingclient/
// api/BillingClientImpl.smali (source tag `billing@@8.0.0`).
//
// zz* risk (documented): launchBillingFlow reads the SKU through
// BillingFlowParams.zzk()/zzj() and BillingFlowParams$ProductDetailsParams.zza()
// — these are Google's own R8-minified names shipped inside the published AAR,
// stable for billing 8.0.0 but version-locked. Re-verify if the billing lib
// version changes. All other construction uses public API only
// (BillingResult.newBuilder(), QueryProductDetailsResult.create(), Purchase ctor).

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * (classes.dex, smali line 8485, .registers 33 — p0=v30, p1=v31, p2=v32).
 * PRIMARY hook. Task 1: fabricate a valid Purchase for the tapped SKU and
 * deliver it synchronously through the registered PurchasesUpdatedListener
 * (reachable via this.zzf → zzs.zzd(), verified) so C# grants the product
 * instantly and no Google Play sheet ever opens.
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
 * ProductDetailsResponseListener)V (classes.dex, smali line 10562, .registers 9).
 * Task 3: return a fake ProductDetails catalog so the C# store populates and
 * the Buy buttons enable. SKU-agnostic: product ids/types are read from
 * p1.zza() → zzbt (implements java.util.List) of QueryProductDetailsParams$Product.
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
 * AcknowledgePurchaseResponseListener)V (classes.dex, smali line 7199, .registers 9).
 * Task 2a: fire the listener with BillingResult OK so the C# transaction
 * completes without the real Play service rejecting the fake token.
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
 * (classes.dex, smali line 7248, .registers 9).
 * Task 2b: same as acknowledge — fire OK with the purchase token from the
 * params so consumables complete instantly.
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
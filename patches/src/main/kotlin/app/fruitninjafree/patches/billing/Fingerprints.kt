package app.fruitninjafree.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 8.3.0 (source tag billing@@8.3.0) — Fruit Ninja Free 3.97.9 ─
//
// The only target is the SDK's own public `BillingClientImpl.launchBillingFlow`
// — never obfuscated, unique by (name, returnType, parameters) within the
// class. Smali verified in
// analysis/com.halfbrick.fruitninjafree/smali/classes2/com/android/billingclient/api/
// BillingClientImpl.smali (method at line 10014, .registers 34, p0=v31, p1=v32,
// p2=v33 — LARGE try/catch table, see the patch's helper-method KDoc).
//
// The `zzcw` testing wrapper (classes2/zzcw.smali:1130) routes its
// launchBillingFlow through `invoke-super` into this method, so the single hook
// below covers both the direct and the wrapper path (verified).

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * (smali line 10014).
 *
 * PRIMARY (and only) Java hook of Fruit Ninja Free IAP: forge an OK
 * `BillingResult` + a fake `Purchase` for the tapped SKU and deliver it through
 * the registered `PurchasesUpdatedListener`
 * (`this.zzf:Lcom/android/billingclient/api/zzab;` → `zzab.zzf()` →
 * `onPurchasesUpdated`, smali 10028-10034) so Unity IAP hands the purchase to
 * the game synchronously — no Google Play payment sheet ever opens.
 *
 * The single body filter pins billing-8.3.0's listener-getter sequence:
 * `invoke-virtual zzab.zzf()Lcom/android/billingclient/api/PurchasesUpdatedListener;`
 * (the package-private getter, zzab.smali:115 — stable for this billing
 * version; `name`+`returnType`+`parameters` already identify the method
 * uniquely inside `BillingClientImpl`, so the filter only guards against a
 * silently-changed body). NOTE: an `onPurchasesUpdated` filter does NOT exist
 * in this method — the callback is invoked from inside `GooglePurchaseUpdatedListener`
 * on the C# side, not here; do not add one.
 *
 * ⚠️ The zz* names are Google's R8-minified names in the published
 * billing-8.3.0 AAR — stable for this APK version (compatibility is locked to
 * 3.97.9/88485200), re-derive if the billing lib version ever changes.
 */
object LaunchBillingFlowFingerprint : Fingerprint(
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf(
        "Landroid/app/Activity;",
        "Lcom/android/billingclient/api/BillingFlowParams;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzab;",
            name = "zzf",
        ),
    ),
)

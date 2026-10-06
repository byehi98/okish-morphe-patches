package app.grimvalor.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Grimvalor 1.2.13 — in-app purchase relay ────────────────────────────────
//
// Everything lives in the developer's own bridge package
// `com.direlight.androidservices` inside **classes2.dex**
// (smali/classes2/com/direlight/androidservices/StoreHelper.smali).
// That package is NOT obfuscated — it ships as a plain AAR — so both targets
// are already unique by (class, name, signature). Body filters are still
// supplied so the fingerprint stays self-documenting and fails loudly rather
// than silently matching a future rewrite of the same method name.
//
// Filter order mirrors the smali instruction order exactly, in every case.
//
// NOTE on `fieldAccess(smali = …)`: the analysis draft used
// `opcode = Opcode.IGET`, which does **not** match — the real instructions are
// `iget-object` / `iget-boolean`, which are distinct Opcodes. The `smali =` form
// is used instead so the opcode is unconstrained and the field signature alone
// identifies the access.

/**
 * [StoreHelper.queryPurchases(QueryPurchasesCompletionHandler)][StoreHelper]
 * (`classes2/com/direlight/androidservices/StoreHelper.smali:905`,
 * `.registers 6` → 4 true locals `v0..v3`, `p0`=`v4` (this), `p1`=`v5`).
 *
 * Original body: marshal onto the main looper, clear `purchasesMap`, build a
 * `QueryPurchasesParams("inapp")`, and hand it to
 * `BillingClient.queryPurchasesAsync` together with a `StoreHelper$3` listener.
 * The listener turns Play's answer into `StorePurchase` objects and calls the
 * `QueryPurchasesCompletionHandler` back with them.
 *
 * Verified instruction order (smali lines 911 → 944):
 *   `Looper.getMainLooper` → `iget-object …purchasesMap` → `HashMap.clear` →
 *   `QueryPurchasesParams.newBuilder` → `BillingClient.queryPurchasesAsync`.
 */
object StoreHelperQueryPurchasesFingerprint : Fingerprint(
    definingClass = "Lcom/direlight/androidservices/StoreHelper;",
    name = "queryPurchases",
    returnType = "V",
    parameters = listOf("Lcom/direlight/androidservices/StoreHelper\$QueryPurchasesCompletionHandler;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        methodCall(definingClass = "Landroid/os/Looper;", name = "getMainLooper"),
        fieldAccess(smali = "Lcom/direlight/androidservices/StoreHelper;->purchasesMap:Ljava/util/HashMap;"),
        methodCall(definingClass = "Ljava/util/HashMap;", name = "clear"),
        methodCall(definingClass = "Lcom/android/billingclient/api/QueryPurchasesParams;", name = "newBuilder"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "queryPurchasesAsync")
    )
)

/**
 * [StoreHelper.launchPurchaseFlow(Activity, String)][StoreHelper]
 * (`classes2/com/direlight/androidservices/StoreHelper.smali:536`,
 * `.registers 5` → 2 true locals `v0..v1`, `p0`=`v2` (this), `p1`=`v3`
 * (Activity, unused by the patched body), `p2`=`v4` (product id)).
 *
 * Original body: bail out if a flow is already running, look `p2` up in
 * `productDetailsMap`, report `"Unknown product"` when it is missing, otherwise
 * build `BillingFlowParams` and open the Play purchase sheet.
 *
 * Verified instruction order (smali lines 537 → 620):
 *   `iget-boolean …purchaseFlowInProgress` → `iget-object …productDetailsMap` →
 *   `const-string "Unknown product"` → `BillingClient.launchBillingFlow`.
 */
object StoreHelperLaunchPurchaseFlowFingerprint : Fingerprint(
    definingClass = "Lcom/direlight/androidservices/StoreHelper;",
    name = "launchPurchaseFlow",
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        fieldAccess(smali = "Lcom/direlight/androidservices/StoreHelper;->purchaseFlowInProgress:Z"),
        fieldAccess(smali = "Lcom/direlight/androidservices/StoreHelper;->productDetailsMap:Ljava/util/HashMap;"),
        string("Unknown product"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "launchBillingFlow")
    )
)
package app.vitamahjong.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Vita Mahjong 3.43.0 — Learnings purchase bridge (`classes8`).
//
// `com.learnings.unity.unikit.module.PurchaseModuleBridge` is called from Unity
// C# by exact class/method name, so these descriptors are NOT obfuscated:
//   smali/classes8/.../PurchaseModuleBridge.smali:170  b(String,String)V
//   smali/classes8/.../PurchaseModuleBridge.smali:696  queryAllPurchases()V
//   smali/classes8/.../PurchaseModuleBridge$1.smali     onSuccess(PurchaseData)V
//   smali/classes8/.../PurchaseModuleBridge$2.smali     onSuccess(List)V
// ============================================================================

/**
 * `PurchaseModuleBridge.queryAllPurchases()V` — restore/ownership query
 * (`classes8`).
 *
 * Public static, no parameters. The shipped body logs the literal
 * `"queryAllPurchases"` and then posts a Runnable (`c0`) to the UI thread via
 * `UnityUtil.runOnUiThread`. Both markers are in instruction order.
 */
object RestorePurchasesFingerprint : Fingerprint(
    definingClass = "Lcom/learnings/unity/unikit/module/PurchaseModuleBridge;",
    name = "queryAllPurchases",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("queryAllPurchases"),
        methodCall(
            definingClass = "Lcom/learnings/unity/unikit/util/UnityUtil;",
            name = "runOnUiThread",
        ),
    )
)

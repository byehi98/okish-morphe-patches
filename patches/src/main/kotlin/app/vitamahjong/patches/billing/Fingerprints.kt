package app.vitamahjong.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Vita Mahjong — Learnings purchase bridge (`classes8`).
//
// `com.learnings.unity.unikit.module.PurchaseModuleBridge` is called from Unity
// C# by exact class/method name, so these descriptors are NOT obfuscated:
//   smali/classes8/.../PurchaseModuleBridge.smali     b(String,String)V
//   smali/classes8/.../PurchaseModuleBridge.smali     queryAllPurchases()V
//   smali/classes8/.../PurchaseModuleBridge$1.smali   onSuccess(PurchaseData)V
//   smali/classes8/.../PurchaseModuleBridge$2.smali   onSuccess(List)V
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

/**
 * `c0.run()V` — the Runnable posted by `queryAllPurchases`.
 *
 * `c0` is the R8 synthetic Runnable created for `queryAllPurchases`; its
 * `run()` calls the synthetic `PurchaseModuleBridge.d()`, which builds the
 * real `PurchaseModuleBridge$2` listener and asks Play Billing for owned
 * purchases. Patching `run()` (not `queryAllPurchases`) preserves the stock
 * `UnityUtil.runOnUiThread` hand-off, so the synthetic purchase list is still
 * delivered on the UI thread exactly like a real restore callback.
 *
 * The class name is R8-generated and therefore version-pinned. If a future
 * build renames it, this fingerprint fails loudly instead of silently
 * mispatching.
 */
object RestoreRunnableFingerprint : Fingerprint(
    definingClass = "Lcom/learnings/unity/unikit/module/c0;",
    name = "run",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/learnings/unity/unikit/module/PurchaseModuleBridge;",
            name = "d",
        ),
    )
)

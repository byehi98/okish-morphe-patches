package app.vitamahjong.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Vita Mahjong 3.44.1 (versionCode 1927) — Learnings purchase bridge
// (`classes8.dex`, `analysis/vita_3441/work/s8`).
//
// `com.learnings.unity.unikit.module.*` is called from Unity C# by exact
// class/method name, so these descriptors are NOT obfuscated. Read from:
//
//   s8/com/learnings/unity/unikit/module/PurchaseModuleBridge.smali:412  d()V
//   s8/com/learnings/unity/unikit/module/PurchaseModuleBridge.smali:696  queryAllPurchases()V
//   s8/com/learnings/unity/unikit/module/PurchaseModuleBridge$2.smali    onSuccess(List)
//   s8/com/learnings/unity/unikit/module/c0.smali                      run()V
// ============================================================================

/**
 * `PurchaseModuleBridge.queryAllPurchases()V` — the C#-visible restore entry
 * point (`classes8`).
 *
 * Public static, no parameters. The shipped body logs the literal
 * `"queryAllPurchases"` and posts the R8 synthetic Runnable `c0` through
 * `UnityUtil.runOnUiThread`. Both markers are in instruction order.
 *
 * **This method is never patched.** It is fingerprinted only so the patch can
 * fail loudly if the bridge is reshaped: the asynchronous UI-thread hand-off it
 * performs is exactly what makes the fake restore callback safe.
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
 * `c0` is the R8 synthetic Runnable created for `queryAllPurchases`; its stock
 * `run()` is a single `invoke-static PurchaseModuleBridge.d()`, which builds the
 * real `PurchaseModuleBridge$2` listener and asks Play Billing for owned
 * purchases. Patching `run()` (not `queryAllPurchases`) preserves the stock
 * `UnityUtil.runOnUiThread` hand-off, so the synthetic purchase list is still
 * delivered on the UI thread exactly where a real restore callback would be.
 *
 * `c0` is `public final synthetic` and its name is R8-generated, so it is
 * version-pinned. If a future build renames it, this fingerprint fails loudly
 * instead of silently mispatching.
 *
 * `.catch`: none — `run()` is a single invoke plus `return-void`.
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

/**
 * Throws [PatchException] unless [this] holds exactly one match.
 *
 * A silently unresolved restore hook would ship an APK where "Restore Purchases"
 * still talks to Play Billing and reports nothing owned, while the patch claims
 * otherwise — a hard failure is strictly better.
 */
internal fun List<Match>?.requireExactlyOne(target: String, patchName: String) {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "$patchName: $target matched $count time(s), expected exactly 1. The Learnings" +
                " purchase bridge has changed shape — refusing to emit an APK whose restore" +
                " behaviour is only half patched."
        )
    }
}

package app.plagueinc.patches.misc

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import app.morphe.patcher.methodCall

/**
 * Hud.selectSpeedButton(int) — fast-forward click handler. When not premium,
 * shows showFastForwardLockedPopup() and returns.
 *
 * Confirmed smali (classes2/com/miniclip/plagueinc/widget/Hud.smali line ~5160):
 *   invoke-virtual {v0}, Lcom/miniclip/plagueinc/PurchaseManager;->isPremium()Z
 *   move-result v0
 *   if-nez v0, :cond_45          <- jumps to speed-up logic when premium
 */
object SelectSpeedButtonFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/widget/Hud;",
    name = "selectSpeedButton",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("I"),
    filters = listOf(
        methodCall(smali = "Lcom/miniclip/plagueinc/PurchaseManager;->isPremium()Z"),
        opcodeMatchResult()
    )
)

/**
 * Hud.updateMainBar() — refreshes HUD button states. Second fast-forward /
 * premium gate using the same isPremium -> if-nez pattern.
 *
 * Confirmed smali (line ~5895):
 *   invoke-virtual {v1}, Lcom/miniclip/plagueinc/PurchaseManager;->isPremium()Z
 *   move-result v1
 *   if-nez v1, :cond_1b
 */
object UpdateMainBarFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/widget/Hud;",
    name = "updateMainBar",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    filters = listOf(
        methodCall(smali = "Lcom/miniclip/plagueinc/PurchaseManager;->isPremium()Z"),
        opcodeMatchResult()
    )
)

/** Shared filter pair tail: move-result immediately after the isPremium call. */
private fun opcodeMatchResult() =
    app.morphe.patcher.opcode(
        com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT,
        app.morphe.patcher.InstructionLocation.MatchAfterImmediately()
    )

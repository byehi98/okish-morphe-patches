package app.plagueinc.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Forces the premium check result to true at both fast-forward gates in Hud,
 * making this patch independent from the Premium Bypass (and safe to combine
 * with it — overwriting the register with 1 twice is idempotent).
 *
 * Gate 1: selectSpeedButton(I)V line ~5160
 *   isPremium()Z -> move-result v0 -> if-nez v0, :ok -> showFastForwardLockedPopup()
 * Gate 2: updateMainBar()V line ~5895
 *   isPremium()Z -> move-result v1 -> if-nez v1, :cond_1b
 *
 * Inserting const/4 v<reg>, 0x1 before each move-result overwrites the
 * returned value, so if-nez always jumps past the locked popup.
 */
@Suppress("unused")
val plagueIncFastForwardUnlockPatch = bytecodePatch(
    name = "Plague Inc. Unlock Fast-Forward",
    description = "Removes the premium lock from the in-game fast-forward button.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        for (fingerprint in listOf(SelectSpeedButtonFingerprint, UpdateMainBarFingerprint)) {
            val match = fingerprint.instructionMatches.last() // the move-result
            val register = match.getInstruction<OneRegisterInstruction>().registerA

            // NOTE: must insert AFTER the move-result, never between an invoke
            // and its move-result — that fails dex verification (VerifyError).
            fingerprint.method.addInstructions(match.index + 1, "const/4 v$register, 0x1")
        }
    }
}

package app.plagueinc.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

@Suppress("unused")
val plagueIncOutbreakPopupPatch = bytecodePatch(
    name = "Plague Inc. Outbreak Popup Bypass",
    description = "Auto-claims outbreak trial missions and suppresses outbreak purchase/onboarding popups.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        // Trial popup (r0) -> auto-claim + goPlay instead of showing popup.
        val trialPopupMethod = DailyLevelSelectTrialPopupFingerprint.methodOrNull
        if (trialPopupMethod != null) {
            val impl = trialPopupMethod.implementation
            if (impl != null) {
                while (impl.instructions.isNotEmpty()) {
                    trialPopupMethod.removeInstruction(0)
                }
            }
            trialPopupMethod.addInstructions(0, """
                invoke-static {}, Lcom/miniclip/plagueinc/jni/Main;->Click()V
                iget v0, p0, Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->l0:I
                invoke-static {v0}, Lcom/miniclip/plagueinc/jni/GameSetup;->claimMissionTrial(I)V
                invoke-virtual {p0}, Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->l0()V
                return-void
            """)
        }

        // Onboarding popup trigger in on-show-page (R) -> force false.
        val onShowFp = DailyLevelSelectOnShowPageFingerprint
        if (onShowFp.methodOrNull != null && onShowFp.instructionMatches.size >= 2) {
            val moveMatch = onShowFp.instructionMatches[1]
            onShowFp.method.addInstructions(moveMatch.index + 1, "const/4 p1, 0x0")
        }

        // Failsafe stubs.
        DailyLevelSelectShowPurchasePopupFingerprint.methodOrNull?.returnEarly()
        DailyLevelSelectShowOnboardingPopupFingerprint.methodOrNull?.returnEarly()
        PurchaseManagerBeginOutbreakPurchaseUxFingerprint.methodOrNull?.returnEarly()
    }
}

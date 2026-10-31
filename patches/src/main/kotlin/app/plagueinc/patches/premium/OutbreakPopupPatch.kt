package app.plagueinc.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Outbreak (daily mission) popup suppression + auto-claim.
 *
 * Primary fix is EngineEntitlementSyncPatch (SKU replay). This patch is the
 * Java-side failsafe: it auto-claims trial/bonus missions instead of showing
 * the claim popup, and stubs the purchase/onboarding popups and the billing
 * entry point so no Google Play sheet can ever appear.
 */
@Suppress("unused")
val plagueIncOutbreakPopupPatch = bytecodePatch(
    name = "Plague Inc. Outbreak Popup Bypass",
    description = "Auto-claims outbreak trial/bonus missions and suppresses outbreak purchase/onboarding popups.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        // Trial branch: claimMissionTrial(focusedSeed) + goPlay() instead of popup.
        val trialMatch = DailyLevelSelectTrialBranchFingerprint.instructionMatches.first()
        val trialMethod = DailyLevelSelectTrialBranchFingerprint.method
        trialMethod.removeInstruction(trialMatch.index)
        trialMethod.addInstructions(trialMatch.index, """
            iget v0, p0, Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->focusedSeed:I
            invoke-static {v0}, Lcom/miniclip/plagueinc/jni/GameSetup;->claimMissionTrial(I)V
            invoke-direct {p0}, Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->goPlay()V
        """)

        // Bonus branch: claimMissionBonus(focusedSeed) + goPlay() instead of popup.
        val bonusMatch = DailyLevelSelectBonusBranchFingerprint.instructionMatches.first()
        val bonusMethod = DailyLevelSelectBonusBranchFingerprint.method
        bonusMethod.removeInstruction(bonusMatch.index)
        bonusMethod.addInstructions(bonusMatch.index, """
            iget v0, p0, Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->focusedSeed:I
            invoke-static {v0}, Lcom/miniclip/plagueinc/jni/GameSetup;->claimMissionBonus(I)V
            invoke-direct {p0}, Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->goPlay()V
        """)

        // Failsafe stubs.
        DailyLevelSelectShowPurchasePopupFingerprint.method.returnEarly()
        DailyLevelSelectShowOnboardingPopupFingerprint.method.returnEarly()
        PurchaseManagerBeginOutbreakPurchaseUxFingerprint.method.returnEarly()
    }
}
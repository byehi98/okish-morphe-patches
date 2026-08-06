package app.drdriving.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.drdriving.patches.shared.Constants.COMPATIBILITY_DRDRIVING

/**
 * Dr. Driving — Unlimited Coins
 *
 * Two layers:
 *
 * 1) Balance inflation: DrDrivingActivity.loadLocal()V — the save-load method
 *    that runs on every app start. The freshly loaded save is inflated to
 *    1,000,000,000 coins (iCoin AND iCoinC) right after mSaveGame is built,
 *    so the HUD shows a huge balance immediately.
 *
 * 2) Spend prevention: at every coin-spend site (car buy, car upgrade, car
 *    rental, repairs) the cost register is zeroed right before the
 *    affordability check. Purchases/repairs are always affordable AND never
 *    deduct coins.
 *
 * The cloud-save reconcile compares iBoughtGold (handled by the Gold patch),
 * not coins, so this never conflicts with the patched economy.
 *
 * Targets (all verified in smali, see notes/coins-unlimited.md):
 * - DrDrivingActivity.loadLocal()V — inflation point
 * - d.buyCar(I)V         — coin branch, cost register v1
 * - d.upgradeCar(II)V    — cost register v0 (iGetUpgradeCost result)
 * - d.rentCar(I)V        — coin branch, cost register v0
 * - d.processCarSelect()V  — repair spend, cost register v0
 * - d.processMultiEnding()V — repair spend, cost register v0
 */
@Suppress("unused")
val drDrivingUnlimitedCoinsPatch = bytecodePatch(
    name = "Dr. Driving Unlimited Coins",
    description = "Coins inflated to 1,000,000,000 on save load; purchases, upgrades, rentals and repairs never cost coins.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DRDRIVING)

    execute {
        // loadLocal has .locals 3 (v0-v2) + p0. Last fingerprint match is the
        // sput-object mSaveGame; insert right after it, before return-void.
        // SaveSettings(Z)V persists the inflated save (no cloud upload).
        LoadLocalFingerprint.method.addInstructions(
            LoadLocalFingerprint.instructionMatches[2].index + 1,
            """
                sget-object v1, Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;
                const v0, 0x3B9ACA00
                iput v0, v1, Lcom/ansangha/drdriving/o;->iCoin:I
                iput v0, v1, Lcom/ansangha/drdriving/o;->iCoinC:I
                const/4 v2, 0x1
                invoke-virtual {p0, v2}, Lcom/ansangha/drdriving/DrDrivingActivity;->SaveSettings(Z)V
            """.trimIndent()
        )

        // buyCar: zero the coin price (v1) at the affordability SUB_INT.
        // The check becomes 0 <= balance (always passes) and the spend
        // subtracts 0 — car is bought for free.
        BuyCarFingerprint.method.addInstructions(
            BuyCarFingerprint.instructionMatches[3].index,
            "const/4 v1, 0x0"
        )

        // upgradeCar: zero the upgrade cost (v0) at the affordability SUB_INT.
        UpgradeCarFingerprint.method.addInstructions(
            UpgradeCarFingerprint.instructionMatches[3].index,
            "const/4 v0, 0x0"
        )

        // rentCar: zero the rental cost (v0) at the affordability SUB_INT.
        RentCarFingerprint.method.addInstructions(
            RentCarFingerprint.instructionMatches[3].index,
            "const/4 v0, 0x0"
        )

        // processCarSelect: zero the repair cost (v0) at the affordability SUB_INT.
        ProcessCarSelectRepairFingerprint.method.addInstructions(
            ProcessCarSelectRepairFingerprint.instructionMatches[2].index,
            "const/4 v0, 0x0"
        )

        // processMultiEnding: zero the repair cost (v0) at the affordability SUB_INT.
        ProcessMultiEndingRepairFingerprint.method.addInstructions(
            ProcessMultiEndingRepairFingerprint.instructionMatches[2].index,
            "const/4 v0, 0x0"
        )
    }
}

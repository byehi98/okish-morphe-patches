package app.drdriving.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.drdriving.patches.shared.Constants.COMPATIBILITY_DRDRIVING

/**
 * Dr. Driving — Unlimited Gold
 *
 * Primary target: DrDrivingActivity.loadLocal()V — the save-load method that
 * runs on every app start. We inflate the freshly loaded save to 1,000,000,000
 * gold (iGoldC, iGold AND iBoughtGold) right after mSaveGame is built. The HUD
 * shows the huge balance immediately — no store interaction required.
 *
 * The game has an anti-cheat gate in CheckAd()V (the per-frame update loop):
 * if (iBoughtGold > 15000) OR (iGold - rs > 15000) it calls finish() and
 * exits the app. We neutralize it by raising the threshold register (v3,
 * const/16 0x3a98) to Int.MAX_VALUE — the gate never fires for our balances,
 * and the rest of CheckAd (quit handling etc.) keeps working.
 *
 * iBoughtGold is inflated too so the Firestore cloud-save reconcile (which
 * picks the save with higher iBoughtGold) keeps the patched local save.
 *
 * Backup targets (store buy path, in case the store is opened):
 * - GoToPurchase(I)V short-circuited at entry: grants 1B gold and returns
 *   before the Play billing flow starts.
 * - onConsumeResponse (real purchase consume) also grants 1B.
 *
 * Targets (verified in smali, see notes/gold-unlimited.md):
 * - DrDrivingActivity.loadLocal()V               (DrDrivingActivity.smali:1028)
 * - DrDrivingActivity.CheckAd()V anti-cheat gate  (DrDrivingActivity.smali:2419)
 * - DrDrivingActivity.GoToPurchase(I)V           (DrDrivingActivity.smali:4002)
 * - DrDrivingActivity.onConsumeResponse(...)V    (DrDrivingActivity.smali:6098)
 */
@Suppress("unused")
val drDrivingUnlimitedGoldPatch = bytecodePatch(
    name = "Dr. Driving Unlimited Gold",
    description = "Gold is inflated to 1,000,000,000 on save load; gold packs in the store also grant gold.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DRDRIVING)

    execute {
        // Neutralize the CheckAd anti-cheat gate FIRST (it runs every frame
        // and would otherwise exit the app the moment balances are inflated).
        // Insert a 32-bit const AFTER const/16 v3, 0x3a98 so v3 = Int.MAX;
        // both gate comparisons then always pass. v3 is clobbered later in
        // CheckAd so this only affects the gate.
        CheckAdAntiCheatFingerprint.method.addInstructions(
            CheckAdAntiCheatFingerprint.instructionMatches[0].index + 1,
            "const v3, 0x7fffffff"
        )

        // loadLocal has .locals 3 (v0-v2) + p0. Last fingerprint match is the
        // sput-object mSaveGame; insert right after it, before return-void.
        // SaveSettings(Z)V takes the boolean in v2 and persists the inflated
        // save (no cloud upload — isSignedIn() is false on this device).
        LoadLocalFingerprint.method.addInstructions(
            LoadLocalFingerprint.instructionMatches[2].index + 1,
            """
                sget-object v1, Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;
                const v0, 0x3B9ACA00
                iput v0, v1, Lcom/ansangha/drdriving/o;->iGoldC:I
                iput v0, v1, Lcom/ansangha/drdriving/o;->iGold:I
                iput v0, v1, Lcom/ansangha/drdriving/o;->iBoughtGold:I
                const/4 v2, 0x1
                invoke-virtual {p0, v2}, Lcom/ansangha/drdriving/DrDrivingActivity;->SaveSettings(Z)V
            """.trimIndent()
        )

        // Backup 1: GoToPurchase has .locals 3 (v0-v2) + p0, p1. Inject grant at entry.
        GoToPurchaseFingerprint.method.addInstructions(0, """
            sget-object v1, Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;
            const v0, 0x3B9ACA00
            iput v0, v1, Lcom/ansangha/drdriving/o;->iGoldC:I
            iput v0, v1, Lcom/ansangha/drdriving/o;->iGold:I
            iput v0, v1, Lcom/ansangha/drdriving/o;->iBoughtGold:I
            const/4 v2, 0x1
            invoke-virtual {p0, v2}, Lcom/ansangha/drdriving/DrDrivingActivity;->SaveSettings(Z)V
            return-void
        """.trimIndent())

        // Backup 2: any consumed purchase also grants 1B gold.
        // onConsumeResponse has .locals 3 (v0-v2) + p0, p1, p2.
        OnConsumeResponseFingerprint.method.addInstructions(0, """
            sget-object v1, Lcom/ansangha/drdriving/DrDrivingActivity;->mSaveGame:Lcom/ansangha/drdriving/o;
            const v0, 0x3B9ACA00
            iput v0, v1, Lcom/ansangha/drdriving/o;->iGoldC:I
            iput v0, v1, Lcom/ansangha/drdriving/o;->iGold:I
            iput v0, v1, Lcom/ansangha/drdriving/o;->iBoughtGold:I
            const/4 v2, 0x1
            invoke-virtual {p0, v2}, Lcom/ansangha/drdriving/DrDrivingActivity;->SaveSettings(Z)V
            return-void
        """.trimIndent())
    }
}

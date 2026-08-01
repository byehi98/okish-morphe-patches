package app.swordigo.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.swordigo.patches.shared.Constants.COMPATIBILITY_SWORDIGO
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val swordigoPremiumPatch = bytecodePatch(
    name = "Swordigo Premium Unlock",
    description = "Enables ad-free, guiding compass, and coin doubler without purchase.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWORDIGO)

    execute {
        // ── Block ads ────────────────────────────────────────────────────────

        CanShowInterstitialFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        ShowAppOpenAdFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        AdsHelperStartFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            iput-boolean v0, p0, Lcom/touchfoo/swordigo/AdsHelper;->_adsDisabledBecausePurchase:Z
        """.trimIndent())

        // ── Fake purchases on game thread ─────────────────────────────────────
        //
        // MainActivity$16$8.run() is what the real store calls on the game thread
        // just before finishedRestoringPurchases(). We inject productPurchased for
        // all 3 items right before that call — so the engine always sees them as
        // owned regardless of what the store returned. Only v0 is available.
        FinishedRestoringRunnableFingerprint.method.addInstructions(0, """
            const-string v0, "noads"
            invoke-static {v0}, Lcom/touchfoo/swordigo/Native;->productPurchased(Ljava/lang/String;)V
            const-string v0, "compass"
            invoke-static {v0}, Lcom/touchfoo/swordigo/Native;->productPurchased(Ljava/lang/String;)V
            const-string v0, "coindoubler"
            invoke-static {v0}, Lcom/touchfoo/swordigo/Native;->productPurchased(Ljava/lang/String;)V
        """.trimIndent())

        // ── Also inject at end of onSurfaceCreated (initial load, before store) ──
        val method = OnSurfaceCreatedFingerprint.method
        val returnIndex = method.indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

        method.addInstructionsWithLabels(returnIndex, """
            iget-boolean v0, p0, Lcom/touchfoo/swordigo/GameRenderer;->applicationSetup:Z
            if-eqz v0, :skip
            invoke-static {}, Lcom/touchfoo/swordigo/Native;->startedRestoringPurchases()V
            const-string v0, "noads"
            invoke-static {v0}, Lcom/touchfoo/swordigo/Native;->productPurchased(Ljava/lang/String;)V
            const-string v0, "compass"
            invoke-static {v0}, Lcom/touchfoo/swordigo/Native;->productPurchased(Ljava/lang/String;)V
            const-string v0, "coindoubler"
            invoke-static {v0}, Lcom/touchfoo/swordigo/Native;->productPurchased(Ljava/lang/String;)V
            invoke-static {}, Lcom/touchfoo/swordigo/Native;->finishedRestoringPurchases()V
            :skip
            nop
        """.trimIndent())
    }
}

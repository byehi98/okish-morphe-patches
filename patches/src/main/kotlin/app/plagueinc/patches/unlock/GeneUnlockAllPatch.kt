package app.plagueinc.patches.unlock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Option B gene unlock (robust): earns every locked gene through the game's
 * own native earn path when the gene menu opens.
 *
 * Root cause (analysis/plagueinc/notes/genes-bypass.md): Option A registers
 * "allgenes" in the purchase registry (Unlocks layer, drives store labels),
 * but gene selection renders from Genes.getGeneState() (native gene-progression
 * layer) which still returns LOCKED. This patch genuinely mutates that native
 * state instead of masking it.
 *
 * Injected at entry of GenesMenu.showMainPage(Z)V
 * (classes6/.../menu/GenesMenu.smali line 2421, .registers 7):
 *
 *   const/16 v0, 200              # iteration budget (cap)
 *   :loop
 *   getLockedGeneCount() -> v1
 *   if v1 == 0, :done             # pool drained
 *   if v0 == 0, :done             # budget exhausted (safety)
 *   unlockRandomGene()            # real native earn, one gene
 *   v0--
 *   goto :loop
 *   :done
 *   getAndResetLastUnlockedGene() # drain popup queue (else MainMenu floods
 *                                 # "new gene" popups via showNewGenePopup)
 *   Settings.save()               # persist across restarts
 *   (falls through to original showMainPage body)
 *
 * Safety notes:
 * - Only v0/v1 are clobbered, before the original body re-initialises them
 *   (it starts with `const/4 v0, -0x1`), so the .registers 7 budget is kept.
 * - Entry injection: never between invoke-* and move-result (VerifyError).
 * - Does NOT stub getGeneState (Option C breaks ACTIVE display) and does NOT
 *   touch setGeneState (the write path this loop relies on).
 * - Settings is Lcom/miniclip/plagueinc/jni/Settings; (classes7), save()Z.
 */
@Suppress("unused")
val plagueIncGeneUnlockAllPatch = bytecodePatch(
    name = "Plague Inc. Unlock All Genes",
    description = "Unlocks all genes by earning them through the game's own unlock path when the gene menu opens.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        // Resolve anchors first so a renamed/missing target fails fast.
        GenesLockedCountFingerprint.method
        UnlocksUnlockRandomGeneFingerprint.method

        GenesMenuShowMainPageFingerprint.method.addInstructionsWithLabels(
            0,
            """
            const/16 v0, 0xC8
            :gene_unlock_loop
            invoke-static {}, Lcom/miniclip/plagueinc/jni/Genes;->getLockedGeneCount()I
            move-result v1
            if-eqz v1, :gene_unlock_done
            if-eqz v0, :gene_unlock_done
            invoke-static {}, Lcom/miniclip/plagueinc/jni/Unlocks;->unlockRandomGene()V
            add-int/lit8 v0, v0, -0x1
            goto :gene_unlock_loop
            :gene_unlock_done
            invoke-static {}, Lcom/miniclip/plagueinc/jni/Genes;->getAndResetLastUnlockedGene()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/miniclip/plagueinc/jni/Settings;->save()Z
            move-result v1
            """.trimIndent()
        )
    }
}

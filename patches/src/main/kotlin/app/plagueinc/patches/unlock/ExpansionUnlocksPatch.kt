package app.plagueinc.patches.unlock

import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Unlocks the paid expansions by converting their native JNI checks in
 * Lcom/miniclip/plagueinc/jni/Unlocks; into Java stubs returning true:
 *
 * - hasCureExpansion()Z  — Cure Mode expansion (line 49)
 * - hasPlaguePack()Z     — premium disease pack bundle (line 55)
 * - hasPlayPass()Z       — Google Play Pass entitlement check (line 58)
 * - hasCustomScenarios()Z— custom scenario creation/browsing (line 52)
 *
 * Same technique as the disease type unlock: rewriting the declaration
 * covers every call site (PlayMenu, ScenariosMenu, DiseaseTypeMenu,
 * DiseaseTypeMenuCure, CureInfoMenu, ProgressMenu).
 */
@Suppress("unused")
val plagueIncExpansionUnlocksPatch = bytecodePatch(
    name = "Plague Inc. Unlock Expansions",
    description = "Unlocks Cure Mode, Plague Pack, custom scenarios and bypasses the Play Pass check.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        val unlocksClass = UnlocksClassFingerprint.classDef

        convertNativeUnlockMethodToTrueStub(unlocksClass, "hasCureExpansion")
        convertNativeUnlockMethodToTrueStub(unlocksClass, "hasPlaguePack")
        convertNativeUnlockMethodToTrueStub(unlocksClass, "hasPlayPass")
        convertNativeUnlockMethodToTrueStub(unlocksClass, "hasCustomScenarios")
    }
}

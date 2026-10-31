package app.plagueinc.patches.unlock

import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Unlocks all official scenarios by converting the native JNI check
 * Scenarios.isLocked(String) into a Java stub returning false.
 *
 * Rewriting the declaration (instead of patching every call site) covers
 * all callers at once: ScenariosMenu.selectScenario, getScenarioToUnlock,
 * the refreshList comparator and DiseaseTypeMenu.ShowScenarioButton.
 */
@Suppress("unused")
val plagueIncScenarioUnlockPatch = bytecodePatch(
    name = "Plague Inc. Unlock All Scenarios",
    description = "Unlocks all official scenarios.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        val scenariosClass = ScenariosClassFingerprint.classDef
        convertNativeUnlockMethodToFalseStub(scenariosClass, "isLocked")
    }
}
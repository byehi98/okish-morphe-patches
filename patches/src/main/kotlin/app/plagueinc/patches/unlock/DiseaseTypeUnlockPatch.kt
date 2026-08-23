package app.plagueinc.patches.unlock

import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Unlocks the paid disease types by converting the native JNI check
 * Unlocks.isAvailable(String) into a Java stub returning true.
 *
 * Confirmed smali (classes3/com/miniclip/plagueinc/jni/Unlocks.smali line 61):
 *   .method public static native isAvailable(Ljava/lang/String;)Z
 *
 * Rewriting the declaration (instead of patching every call site) covers all
 * callers at once: MainMenu, DiseaseTypeMenu, DiseaseTypeMenuCure,
 * ScenariosMenu, SpeedrunScoreboardMenu, DifficultyMenu — unlocking
 * Necroa (zombie), Neurax Worm, Simian Flu, Shadow Plague (vampire) and
 * Xenolith without owning the purchases.
 *
 * Caveat: the C++ engine may keep its own internal unlock state for some
 * content; this guarantees the Java/UI layer treats everything as available.
 */
@Suppress("unused")
val plagueIncDiseaseTypeUnlockPatch = bytecodePatch(
    name = "Plague Inc. Unlock Disease Types",
    description = "Unlocks all disease types including Neurax Worm, Necroa Virus, Simian Flu and Shadow Plague.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        convertNativeUnlockMethodToTrueStub(
            UnlocksClassFingerprint.classDef,
            "isAvailable"
        )
    }
}

package app.plagueinc.patches.premium

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Forces the app into premium mode.
 *
 * PurchaseManager.isPremium() always returns true — this removes every
 * Java-side gate: fast-forward lock popup, "Unlock Premium" buttons in
 * DiseaseTypeMenu / GenesMenu / ScenariosMenu / DiseaseTypeMenuCure,
 * mega-brutal difficulty toggle, Ndemic ad background.
 *
 * NOTE: we deliberately do NOT call Main.setIsPremium(true) (native) at init.
 * The game only calls it after a completed purchase, once the engine is fully
 * ready; firing it during MainActivity.onCreate risks poking engine subsystems
 * before async init completes (observed as a crash when starting a game).
 */
@Suppress("unused")
val plagueIncPremiumBypassPatch = bytecodePatch(
    name = "Plague Inc. Premium",
    description = "Unlocks all premium features and disables premium prompts.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        // All Java-side premium checks return true
        IsPremiumFingerprint.method.returnEarly(true)
    }
}

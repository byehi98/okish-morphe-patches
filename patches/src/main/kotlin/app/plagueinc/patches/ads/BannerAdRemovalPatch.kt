package app.plagueinc.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Removes banner ads shown during gameplay.
 *
 * - onGameSessionStarted(): creates + loads the AppLovin MAX banner view
 *   (ad unit "e21aea1fadb44ed2") every time a game session starts.
 * - showNdemicAd(): fallback Ndemic promo image displayed instead of a real
 *   banner when ad fill fails.
 *
 * Both are made to return immediately, so no banner view is ever created,
 * loaded or attached.
 */
@Suppress("unused")
val plagueIncBannerAdRemovalPatch = bytecodePatch(
    name = "Plague Inc. Remove Banner Ads",
    description = "Removes AppLovin banner ads during gameplay.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        OnGameSessionStartedFingerprint.method.returnEarly()
        ShowNdemicAdFingerprint.method.returnEarly()
    }
}

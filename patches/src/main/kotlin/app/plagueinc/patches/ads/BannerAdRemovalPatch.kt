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
 * - showNdemicAd(): fallback Ndemic promo image (`premium_ad_bg_game`,
 *   "go premium") displayed instead of a real banner when ad fill fails.
 *
 * The real AppLovin banner is killed by bailing out of onGameSessionStarted()
 * before the MaxAdView is ever created/loaded/attached.
 *
 * IMPORTANT: the Ndemic `premium_ad_bg_game` ImageView is declared VISIBLE by
 * default in res/layout/hud.xml (no android:visibility attribute), so it
 * renders even though no ad loads. The app only hides it via hideNdemicAd(),
 * which originally runs from onAdLoaded()/onGameSessionEnded(). Because we
 * never create an ad view, onAdLoaded never fires — leaving the promo visible
 * for the whole session (it only disappears after the first session ends, then
 * stays hidden). That is the "banner sometimes shows up" symptom.
 *
 * To fix it we also call hideNdemicAd() at the top of onGameSessionStarted()
 * (before bailing), so the promo is hidden the instant a game session starts.
 * showNdemicAd() is still forced to no-op as a belt-and-suspenders guard.
 */
@Suppress("unused")
val plagueIncBannerAdRemovalPatch = bytecodePatch(
    name = "Plague Inc. Remove Banner Ads",
    description = "Removes AppLovin banner ads and the Ndemic premium promo during gameplay.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        val onGameSessionStartedMethod = OnGameSessionStartedFingerprint.method
        // Bail out before any ad view is created (kills the real AppLovin banner).
        onGameSessionStartedMethod.returnEarly()
        // Also hide the Ndemic "go premium" promo ImageView (layout-default VISIBLE)
        // so it never renders during gameplay.
        onGameSessionStartedMethod.addInstructions(
            0,
            "invoke-direct {p0}, Lcom/miniclip/plagueinc/AdController;->hideNdemicAd()V"
        )

        ShowNdemicAdFingerprint.method.returnEarly()
    }
}

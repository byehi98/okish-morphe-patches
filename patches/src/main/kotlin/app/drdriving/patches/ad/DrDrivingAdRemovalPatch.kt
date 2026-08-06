package app.drdriving.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.drdriving.patches.shared.Constants.COMPATIBILITY_DRDRIVING

/**
 * Dr. Driving — Remove Ads
 *
 * Master switch + defense in depth. Stubbing initAds() prevents the ad
 * system from ever initializing; the remaining stubs (interstitial show/load,
 * rewarded load, banner creation, banner show) are belt-and-suspenders in
 * case any ad code path is reached independently.
 *
 * Note: this removes ads but does NOT grant rewarded-ad rewards (the in-game
 * "ad balloon" rewards are granted by d.processAdBalloon, only reachable after
 * a real rewarded ad completes). For ad-free rewarded rewards, the
 * showVideo/onVideoWatched pairing would be needed — out of scope here.
 *
 * All targets are in the stable, non-obfuscated DrDrivingActivity.
 */
@Suppress("unused")
val drDrivingAdRemovalPatch = bytecodePatch(
    name = "Dr. Driving Ad Removal",
    description = "Removes all Google AdMob ads (banner, interstitial, rewarded).",
    default = true
) {
    compatibleWith(COMPATIBILITY_DRDRIVING)

    execute {
        // Master switch — no ad system at all.
        InitAdsFingerprint.method.addInstructions(0, "return-void")

        // Interstitial: never show, never load.
        ShowInterstitialFingerprint.method.addInstructions(0, "return-void")
        RequestNewInterstitialFingerprint.method.addInstructions(0, "return-void")

        // Rewarded: never load (show gate is gated on a loaded ad).
        RequestNewRewardFingerprint.method.addInstructions(0, "return-void")

        // Banner: never create, never show.
        CreateAdMobBannerViewFingerprint.method.addInstructions(0, "return-void")
        ShowAdFingerprint.method.addInstructions(0, "return-void")
    }
}

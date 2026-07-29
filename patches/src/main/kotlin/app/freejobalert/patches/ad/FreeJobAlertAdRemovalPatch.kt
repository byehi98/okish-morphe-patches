package app.freejobalert.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.freejobalert.patches.shared.Constants.COMPATIBILITY_FREEJOBALERT

@Suppress("unused")
val freeJobAlertAdRemovalPatch = bytecodePatch(
    name = "FreeJobAlert Ad Removal",
    description = "Removes all Google AdMob ads (banner, interstitial, rewarded, app open, native).",
    default = true
) {
    compatibleWith(COMPATIBILITY_FREEJOBALERT)

    execute {
        // Smali snippet to return-void immediately — prevents any ad from loading
        val returnVoid = "return-void"

        // ── Flutter Google Mobile Ads — all ad formats ────────────────────────
        // Each load() call is intercepted and returned immediately so no ad
        // request is ever made to Google's servers.

        FlutterBannerAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterInterstitialAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterRewardedAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterRewardedInterstitialAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterAppOpenAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterNativeAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterAdManagerBannerAdLoadFingerprint.method.addInstructions(0, returnVoid)
        FlutterAdManagerInterstitialAdLoadFingerprint.method.addInstructions(0, returnVoid)

        // ── Legacy AdMob mediation adapter ────────────────────────────────────
        AbstractAdViewAdapterRequestBannerFingerprint.methodOrNull
            ?.addInstructions(0, returnVoid)
        AbstractAdViewAdapterRequestInterstitialFingerprint.methodOrNull
            ?.addInstructions(0, returnVoid)
        AbstractAdViewAdapterShowInterstitialFingerprint.methodOrNull
            ?.addInstructions(0, returnVoid)
    }
}

package app.bighunter.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.bighunter.patches.shared.Constants.COMPATIBILITY_BIG_HUNTER

/**
 * Ad removal for Big Hunter across all three network bridges (AdMob, AppLovin, Unity Ads).
 *
 * Instead of a bare no-op, each show() immediately calls the bridge's own native
 * stopProcess() and returns — mirroring the bridge's existing "ad not ready" path — so the
 * native side instantly learns the ad flow is finished and gameplay never stalls waiting
 * for a dismiss callback. The interstitial is never displayed.
 */
@Suppress("unused")
val bigHunterAdRemovalPatch = bytecodePatch(
    name = "Big Hunter Remove Ads",
    description = "Interstitial ads are never shown (show() short-circuits via the ad-not-ready path on all three network bridges: AdMob, AppLovin, Unity Ads).",
) {
    compatibleWith(COMPATIBILITY_BIG_HUNTER)

    execute {
        AdmobShowFingerprint.method.addInstructions(0, """
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsAdmob;->stopProcess()V
            return-void
        """.trimIndent())
        AppLovinShowFingerprint.method.addInstructions(0, """
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsAppLovin;->stopProcess()V
            return-void
        """.trimIndent())
        UnityAdsShowFingerprint.method.addInstructions(0, """
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsUnity;->stopProcess()V
            return-void
        """.trimIndent())
    }
}

/**
 * Rewards without ads for Big Hunter: requesting a rewarded video fires the reward
 * instantly (native rewardCallback()) and closes the flow (native stopProcess()) — the ad
 * itself is never loaded or displayed. Same three-network coverage.
 */
@Suppress("unused")
val bigHunterFreeRewardsPatch = bytecodePatch(
    name = "Big Hunter Rewards Without Ads",
    description = "Rewarded video requests grant the reward immediately via the game's own native rewardCallback() without loading or showing any ad (AdMob, AppLovin, Unity Ads).",
) {
    compatibleWith(COMPATIBILITY_BIG_HUNTER)

    execute {
        AdmobShowWithRewardFingerprint.method.addInstructions(0, """
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsAdmob;->rewardCallback()V
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsAdmob;->stopProcess()V
            return-void
        """.trimIndent())
        AppLovinShowWithRewardFingerprint.method.addInstructions(0, """
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsAppLovin;->rewardCallback()V
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsAppLovin;->stopProcess()V
            return-void
        """.trimIndent())
        UnityAdsShowWithRewardFingerprint.method.addInstructions(0, """
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsUnity;->rewardCallback()V
            invoke-static {}, LkakarodJavaLibs/data/KKJAdsUnity;->stopProcess()V
            return-void
        """.trimIndent())
    }
}

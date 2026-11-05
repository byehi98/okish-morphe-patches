package app.offtheroad.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.offtheroad.patches.shared.Constants.COMPATIBILITY_OFF_THE_ROAD

private const val REWARD_BRIDGE = "Lapp/morphe/extension/offtheroad/RewardBridge;"

/**
 * Off The Road 1.18.2 · **Free Rewarded Ads** — "watch a video" rewards are paid out
 * without showing an ad.
 *
 * Pure DEX plus the `offtheroad` extension (no native edit, all four ABIs covered).
 *
 * ### Why a delayed callback is required
 *
 * The native reward sequence has a strict ordering:
 *
 * ```
 * cApplication::showRewardedVideoAd(type, source, count)      libgame.so 0x375794
 *   ├─ cUnityAds::showRewardedAd / cAdmob::showRewardedAd     ← JNI into these methods
 *   ├─ cAudioEngine::mute
 *   └─ THEN writes VREW_T / VREW_S / VREW_C                   (config writer)
 *
 * cApplication::onRewardEarned()         0x375d0c
 *   └─ cApplication::showClaimVideoAdRewardDialog()   0x3758b4
 *        └─ reads VREW_T / VREW_S / VREW_C back
 * ```
 *
 * Calling `callRewardEarned()` synchronously from the Java show method runs *before*
 * the native write, so the claim dialog would see stale/zero values on the first
 * reward. The patch therefore never invokes the reward callback inline: it hands the
 * bridge object to [RewardBridge], which posts the callback on the main looper a few
 * hundred milliseconds later. By then `showRewardedVideoAd` has unwound and the
 * `VREW_*` keys are correct.
 *
 * ### Targets
 *
 * | Method | Action |
 * |---|---|
 * | `Admob.HasCachedRewarded()Z` | `true` (rewarded button always offered) |
 * | `UnityAds.hasCachedRewardedAd()Z` | `true` |
 * | `Admob.ShowRewarded()V` | `RewardBridge.grantAdmobReward()`; no ad |
 * | `UnityAds.showRewardedAd()V` | `RewardBridge.grantUnityReward()`; no ad |
 *
 * The bridge calls the wrappers' own public callbacks
 * (`Admob.callRewardEarned()` + `callAdFinished()`,
 * `UnityAds.onRewardEarned()` + `onAdFinished()`), which are the exact JNI entry
 * points the SDK listeners use. `onAdFinished` restores audio and the full-screen ad
 * cooldown clock. A short dedupe window in the bridge prevents both paths from
 * double-granting if native ever reaches both show methods for one request.
 *
 * ### Interaction
 *
 * Does not touch any interstitial/banner method, so it combines cleanly with
 * "Remove Ads". Do not combine with buying the `removeads` product through
 * "Free Store": that sets `cApplication+0x54`, after which
 * `isRewardedVideoAdAvailable()` reports false and the reward buttons disappear.
 */
@Suppress("unused")
val offTheRoadFreeRewardedAdsPatch = bytecodePatch(
    name = "Free Rewarded Ads",
    description = "The \"watch a video\" buttons pay out instantly. Tap one and you get the " +
        "cash, repair or ticket reward without any ad or wait.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_OFF_THE_ROAD)

    extendWith("extensions/offtheroad.mpe")

    execute {
        // Advertise a rewarded ad whenever the native UI asks, so the buttons stay enabled.
        AdmobHasCachedRewardedFingerprint.method.returnEarly(true)
        UnityAdsHasCachedRewardedFingerprint.method.returnEarly(true)

        // Skip the SDK entirely and let the bridge fire the reward callbacks after the
        // native VREW_* writes have completed.
        AdmobShowRewardedFingerprint.method.addInstructions(
            0,
            """
            invoke-static {}, $REWARD_BRIDGE->grantAdmobReward()V
            return-void
            """.trimIndent(),
        )

        UnityAdsShowRewardedFingerprint.method.addInstructions(
            0,
            """
            invoke-static {}, $REWARD_BRIDGE->grantUnityReward()V
            return-void
            """.trimIndent(),
        )
    }
}

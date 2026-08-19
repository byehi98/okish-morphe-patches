package app.vector.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ═══════════════════════════════════════════════════════════════════
// P1: AdMob Rewarded Ad Spoof
// ═══════════════════════════════════════════════════════════════════

/**
 * com.google.unity.ads.UnityRewardedAd.show()V — public.
 *
 * The AdMob Unity plugin show entry. Normally calls
 * `activity.runOnUiThread(lambda$show$0)` which does
 * `rewardedAd.setImmersiveMode(true)` + `rewardedAd.show(activity, listener)`.
 * The listener (UnityRewardedAd$4) fires onUserEarnedReward → callback.onUserEarnedReward.
 *
 * Patched: replaces the runOnUiThread + return with synchronous callback chain:
 *   fullScreenContentCallback.onAdShowedFullScreenContent()
 *   callback.onUserEarnedReward("coins", 1.0f)
 *   fullScreenContentCallback.onAdDismissedFullScreenContent()
 *
 * Confirmed smali: classes7/com/google/unity/ads/UnityRewardedAd.smali:580
 * (.registers 3 — v0, v1 locals + p0).
 */
object UnityRewardedAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "show",
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        string("Tried to show rewarded ad before it was ready. This should in theory never happen. If it does, please contact the plugin owners.")
    )
)

// ═══════════════════════════════════════════════════════════════════
// P2: IronSource LevelPlay Rewarded Ad Spoof
// ═══════════════════════════════════════════════════════════════════

/**
 * com.ironsource.unity.androidbridge.RewardedAd.showAd(String)V — public.
 *
 * The LevelPlay rewarded ad show entry. Forwards to
 * `mRewardedAd.showAd(mActivity, placement)` which shows a real ad.
 * Patched to fire IUnityRewardedAdListener callbacks directly:
 *   onAdDisplayed("{}") → onAdRewarded("{}", placement, 1) → onAdClosed("{}")
 *
 * Confirmed smali: classes8/com/ironsource/unity/androidbridge/RewardedAd.smali:175
 * (.registers 4 — v0, v1 locals + p0, p1 params).
 */
object RewardedAdShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "showAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "showAd",
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.RewardedAd.setupRewardedListener(IUnityRewardedAdListener)V — private.
 *
 * Called from RewardedAd constructors with the C# proxy (IUnityRewardedAdListener) as p1.
 * Wraps the proxy in RewardedAd$1 and registers with LevelPlayRewardedAd.setListener().
 * The patch stores the proxy in a new instance field so showAd() can fire the reward lifecycle.
 *
 * Confirmed smali: classes8/com/ironsource/unity/androidbridge/RewardedAd.smali
 */
object RewardedAdSetupListenerFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "setupRewardedListener",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "setListener",
        )
    )
)

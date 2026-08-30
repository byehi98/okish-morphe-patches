package app.trafficrider.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.trafficrider.patches.shared.Constants.COMPATIBILITY_TRAFFICRIDER

/**
 * Traffic Rider — Ad Removal + Instant Rewards
 * (com.skgames.trafficrider v2.11, Unity 6000.2.15f1, Google Mobile Ads Unity bridge)
 *
 * Copies the production-verified Missiles pattern (same com.google.unity.ads.* bridge,
 * classes3.dex in Traffic Rider). Callback-spoofing keeps native C# logic from hanging.
 */
@Suppress("unused")
val trafficRiderAdsPatch = bytecodePatch(
    name = "Traffic Rider Ads Removal + Instant Rewards",
    description = "Removes ads and grants rewarded rewards instantly: interstitial/banner/app-open never show, rewarded videos fire onUserEarnedReward(1.0) instantly without ad.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRAFFICRIDER)

    execute {
        // ── Interstitial ──
        InterstitialLoadAdFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/Interstitial;->callback:Lcom/google/unity/ads/UnityInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onInterstitialAdLoaded()V
            return-void
        """.trimIndent())

        InterstitialShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/Interstitial;->callback:Lcom/google/unity/ads/UnityInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdShowedFullScreenContent()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdImpression()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // ── Rewarded ──
        UnityRewardedAdLoadAdFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onRewardedAdLoaded()V
            return-void
        """.trimIndent())

        UnityRewardedAdShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        UnityRewardedAdGetRewardItemFingerprint.method.addInstructions(0, """
            new-instance v0, Lcom/google/ads/mediation/unity/UnityReward;
            invoke-direct {v0}, Lcom/google/ads/mediation/unity/UnityReward;-><init>()V
            return-object v0
        """.trimIndent())

        // ── Rewarded interstitial ──
        UnityRewardedInterstitialAdLoadAdFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedInterstitialAd;->callback:Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onRewardedInterstitialAdLoaded()V
            return-void
        """.trimIndent())

        UnityRewardedInterstitialAdShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedInterstitialAd;->callback:Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        UnityRewardedInterstitialAdGetRewardItemFingerprint.method.addInstructions(0, """
            new-instance v0, Lcom/google/ads/mediation/unity/UnityReward;
            invoke-direct {v0}, Lcom/google/ads/mediation/unity/UnityReward;-><init>()V
            return-object v0
        """.trimIndent())

        // ── App open ──
        UnityAppOpenAdShowFingerprint.method.addInstructions(0, "return-void")

        // ── Banner ──
        // createAdView nulls adView; loadAd/show posts runnables that dereference it with NPE (Banner$6/$7).
        // Patch all of them to no-op to prevent crash at level-complete banner refresh.
        BannerCreateAdViewFingerprint.method.addInstructions(0, "return-void")
        BannerLoadAdFingerprint.method.addInstructions(0, "return-void")
        BannerShowFingerprint.method.addInstructions(0, "return-void")
        Banner6RunFingerprint.method.addInstructions(0, "return-void")
        Banner7RunFingerprint.method.addInstructions(0, "return-void")
    }
}

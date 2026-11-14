package app.trafficrider.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.trafficrider.patches.shared.Constants.COMPATIBILITY_TRAFFICRIDER

/**
 * Traffic Rider — Ad Removal + Instant Rewards
 * (com.skgames.trafficrider v2.20, Unity 6000.5.11f1, Google Mobile Ads Unity bridge)
 *
 * Copies the production-verified Missiles pattern (same com.google.unity.ads.* bridge,
 * classes3.dex in Traffic Rider). Callback-spoofing keeps native C# logic from hanging.
 *
 * ── Version history ──────────────────────────────────────────────────────────
 * 2.11 shipped com.google.ads.mediation.unity.UnityReward as a dummy RewardItem
 * implementation; 2.20 removed it (the Unity mediation adapter was rewritten), so
 * the old `new-instance UnityReward` hook would throw NoClassDefFoundError the
 * moment C# calls GetRewardItem. Instead we resolve *any* concrete RewardItem
 * implementation shipped in the APK (the GMS Ads SDK always carries one) and use
 * that, falling back to `null` if none exists.
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

        // ── Reward item ──
        // Resolve a concrete RewardItem implementation bundled with the app's GMS
        // Ads SDK. 2.11's `UnityReward` is gone in 2.20, so hardcoding it breaks
        // C#'s GetRewardItem() → getType()/getAmount() path. The dummy SDK impl
        // returns ("", 1) — the same values UnityReward used.
        val rewardItemStub = classDefByOrNull { classDef ->
            "Lcom/google/android/gms/ads/rewarded/RewardItem;" in classDef.interfaces &&
                classDef.methods.any { method ->
                    method.name == "<init>" && method.parameterTypes.isEmpty()
                }
        }?.type?.let { impl ->
            """
                new-instance v0, $impl
                invoke-direct {v0}, $impl-><init>()V
                return-object v0
            """.trimIndent()
        } ?: """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()

        UnityRewardedAdGetRewardItemFingerprint.method.addInstructions(0, rewardItemStub)

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

        UnityRewardedInterstitialAdGetRewardItemFingerprint.method.addInstructions(0, rewardItemStub)

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

        // ══ nextgen bridge (com.google.unity.ads.nextgen) ═════════════════════
        // This is the bridge that is actually live in 2.20: with every legacy
        // loadAd spoofed the game still gets real mediation fills
        // (logcat: "Interstitial ad source: Liftoff Monetize (bidding)
        // com.google.ads.mediation.vungle.VungleMediationAdapter" and a
        // com.vungle.ads.internal.ui.VungleActivity on top), and no legacy
        // code path can produce an ad request. nextgen ads talk to the new
        // Ads Mobile SDK (com.google.android.libraries.ads.mobile.sdk.*), so
        // the legacy hooks never fire.
        //
        // nextgen ads extend UnityAdBase where `callback` is a raw Object, so
        // each hook must check-cast it to the concrete callback interface.
        // Every nextgen callback interface extends UnityFullScreenContentCallback,
        // so the same full-screen trio used by 2.11 works here.

        // ── nextgen: interstitial ──
        NextGenInterstitialLoadFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityInterstitialAdCallback;->onInterstitialAdLoaded()V
            return-void
        """.trimIndent())

        NextGenInterstitialShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityInterstitialAdCallback;->onAdShowedFullScreenContent()V
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityInterstitialAdCallback;->onAdImpression()V
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // ── nextgen: rewarded ──
        NextGenRewardedLoadFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityRewardedAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityRewardedAdCallback;->onRewardedAdLoaded()V
            return-void
        """.trimIndent())

        NextGenRewardedShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityRewardedAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityRewardedAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/nextgen/UnityRewardedAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityRewardedAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // ── nextgen: rewarded interstitial ──
        NextGenRewardedInterstitialLoadFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAdCallback;->onRewardedInterstitialAdLoaded()V
            return-void
        """.trimIndent())

        NextGenRewardedInterstitialShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // ── nextgen: reward item ──
        // getRewardItem() reads the reward off the SDK ad, which never exists
        // because load() is spoofed. Resolve a concrete implementation of the
        // *new* SDK interface from the app's own dex and return that instead.
        // ads_mobile_sdk.j63 has a no-arg ctor; g63/l63 take (int, String) —
        // same (amount, type) shape the real ads return.
        val nextGenRewardItemStub = classDefByOrNull { classDef ->
            "Lcom/google/android/libraries/ads/mobile/sdk/rewarded/RewardItem;" in classDef.interfaces &&
                classDef.methods.any { method ->
                    method.name == "<init>" && method.parameterTypes.isEmpty()
                }
        }?.type?.let { impl ->
            """
                new-instance v0, $impl
                invoke-direct {v0}, $impl-><init>()V
                return-object v0
            """.trimIndent()
        } ?: classDefByOrNull { classDef ->
            "Lcom/google/android/libraries/ads/mobile/sdk/rewarded/RewardItem;" in classDef.interfaces &&
                classDef.methods.any { method ->
                    method.name == "<init>" &&
                        method.parameterTypes.map(CharSequence::toString) ==
                        listOf("I", "Ljava/lang/String;")
                }
        }?.type?.let { impl ->
            """
                new-instance v0, $impl
                const/4 v1, 0x1
                const-string v2, "reward"
                invoke-direct {v0, v1, v2}, $impl-><init>(ILjava/lang/String;)V
                return-object v0
            """.trimIndent()
        } ?: """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()

        NextGenRewardedGetRewardItemFingerprint.method.addInstructions(0, nextGenRewardItemStub)
        NextGenRewardedInterstitialGetRewardItemFingerprint.method.addInstructions(0, nextGenRewardItemStub)

        // ── nextgen: app open ──
        NextGenAppOpenLoadFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityAppOpenAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityAppOpenAdCallback;->onAppOpenAdLoaded()V
            return-void
        """.trimIndent())

        NextGenAppOpenShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/nextgen/UnityAdBase;->callback:Ljava/lang/Object;
            check-cast v0, Lcom/google/unity/ads/nextgen/UnityAppOpenAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityAppOpenAdCallback;->onAdShowedFullScreenContent()V
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityAppOpenAdCallback;->onAdImpression()V
            invoke-interface {v0}, Lcom/google/unity/ads/nextgen/UnityAppOpenAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // ── nextgen: banner ──
        NextGenBannerLoadFingerprint.method.addInstructions(0, "return-void")
        NextGenBannerShowFingerprint.method.addInstructions(0, "return-void")
    }
}

package app.missiles.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.missiles.patches.shared.Constants.COMPATIBILITY_MISSILES

/**
 * Missiles Ads Removal (v2) — AdMob via com.google.unity.ads.* Java bridges.
 *
 * The old IronSource ISDemandOnly* patch was dead code: those methods have ZERO
 * callers (verified across all dex + libil2cpp/global-metadata strings). The real
 * ad stack is Google Mobile Ads (AdMob) called from native IL2CPP through the
 * official `com.google.unity.ads.*` bridge classes in classes2.dex.
 *
 * Strategy: callback-spoofing, not bare return-void — the C# GoogleMobileAds
 * plugin drives game logic off these callbacks (onAdLoaded → show → reward →
 * onAdDismissedFullScreenContent). Firing them synchronously keeps the native
 * game logic from hanging. All injected bodies are self-contained (callback
 * field is read fresh), so leftover original code after our injected block is
 * unreachable dead code and harmless.
 *
 * Register budget per method verified against smali: loadAd() bodies use
 * .registers 5 → p0=v2 (this), v0 free for the callback object.
 * show() bodies use .registers 3 → p0=v2, v0-v1 free. getRewardItem() bodies
 * use .registers 6 → v0-v5 free; the injected body uses v0 for the constructed
 * RewardItem.
 */
@Suppress("unused")
val missilesAdsRemovalPatch = bytecodePatch(
    name = "Missiles Ads Removal",
    description = "Spoofs the Google Mobile Ads Unity bridge (AdMob): interstitial, rewarded, rewarded interstitial, app open and banner ads resolve instantly as loaded/showed/closed/rewarded without ever displaying an ad.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MISSILES)

    execute {
        // ── Interstitial ──────────────────────────────────────────────────────
        // 1. loadAd → pretend the interstitial finished loading.
        InterstitialLoadAdFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/Interstitial;->callback:Lcom/google/unity/ads/UnityInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onInterstitialAdLoaded()V
            return-void
        """.trimIndent())

        // 2. show → fire the full-screen lifecycle so C# closes the ad flow.
        //    (onAdImpression + showed + dismissed; bypasses the null-ad guard)
        InterstitialShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/Interstitial;->callback:Lcom/google/unity/ads/UnityInterstitialAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdShowedFullScreenContent()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdImpression()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // ── Rewarded ──────────────────────────────────────────────────────────
        // 3. loadAd → pretend the rewarded ad finished loading.
        UnityRewardedAdLoadAdFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onRewardedAdLoaded()V
            return-void
        """.trimIndent())

        // 4. show → grant the reward immediately: full-screen callbacks plus
        //    onUserEarnedReward("reward", 1.0f). Type string generic ("reward"):
        //    C# GoogleMobileAds forwards it verbatim; the game's C# reward
        //    handler receives a valid Reward event without any ad being played.
        UnityRewardedAdShowFingerprint.method.addInstructions(0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onAdDismissedFullScreenContent()V
            return-void
        """.trimIndent())

        // 5. getRewardItem → construct a fresh com.google.ads.mediation.unity
        //    UnityReward (public no-arg ctor, amount=1, type="") so the C# layer
        //    reading the reward item after our spoofed show() never sees null and
        //    never blocks on the (now never-populated) rewardedAd field. We build
        //    the object ourselves instead of reading RewardItem.DEFAULT_REWARD:
        //    that static field is R8-renamable and referencing a renamed field is
        //    a NoSuchFieldError at runtime. UnityReward is a public class with a
        //    public constructor — safe to instantiate from any injected package.
        UnityRewardedAdGetRewardItemFingerprint.method.addInstructions(0, """
            new-instance v0, Lcom/google/ads/mediation/unity/UnityReward;
            invoke-direct {v0}, Lcom/google/ads/mediation/unity/UnityReward;-><init>()V
            return-object v0
        """.trimIndent())

        // ── Rewarded interstitial (same shape as rewarded) ────────────────────
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

        // Same as #5: construct a fresh UnityReward instead of reading the
        // R8-fragile RewardItem.DEFAULT_REWARD static field.
        UnityRewardedInterstitialAdGetRewardItemFingerprint.method.addInstructions(0, """
            new-instance v0, Lcom/google/ads/mediation/unity/UnityReward;
            invoke-direct {v0}, Lcom/google/ads/mediation/unity/UnityReward;-><init>()V
            return-object v0
        """.trimIndent())

        // ── App open ──────────────────────────────────────────────────────────
        // 8. show → unconditional no-op. loadAd is deliberately left untouched:
        //    firing a fake onAppOpenAdLoaded could confuse the C# foreground
        //    handler; a no-op show() alone guarantees no ad on foreground.
        UnityAppOpenAdShowFingerprint.method.addInstructions(0, "return-void")

        // ── Banner ────────────────────────────────────────────────────────────
        // 9. createAdView → no-op. adView stays null so Banner.show()/loadAd()
        //    (both internally null-guarded) no-op safely with no banner ever.
        BannerCreateAdViewFingerprint.method.addInstructions(0, "return-void")
    }
}
package app.magicsurvival.patches.ads

import app.magicsurvival.patches.shared.Constants.COMPATIBILITY_MAGIC_SURVIVAL
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly

/**
 * Magic Survival — Remove Ads (v0.9941, Google Mobile Ads Unity bridge).
 *
 * Interstitials, app-open ads and banners are killed at the SOURCE: `loadAd`
 * and `pollAd` (the AdMob preloaded-cache route that bypasses loadAd) are
 * no-ops, so nothing is ever fetched and `isAdAvailable()` reads false — which
 * is exactly the gate the C# side consults before showing. `Banner.show()` is
 * the only call that flips the banner view VISIBLE, so no-op'ing it (plus
 * `Banner.loadAd`) leaves the — deliberately still created — AdView parked in
 * GONE state, where `hide()`/layout callbacks keep finding a non-null view.
 *
 * `show()` on the two full-screen formats still fires the synthetic
 * showed → impression → dismissed lifecycle as insurance: if the game calls
 * `show()` without consulting readiness, its ad state machine resolves in the
 * same frame instead of waiting on a close event that can no longer arrive.
 *
 * Rewarded ads are intentionally NOT touched here — they are the reward
 * source and are handled by `RewardedInstantRewardsPatch`.
 *
 * Register budgets (verified against 0.9941 smali):
 *   Interstitial.loadAd / pollAd / show      .registers 5 / 9 / 3
 *   UnityAppOpenAd.loadAd / pollAd / show    .registers 5 / 9 / 3
 *   Banner.loadAd / show                     .registers ? / 3 → returnEarly
 */
@Suppress("unused")
val magicSurvivalRemoveAdsPatch = bytecodePatch(
    name = "Magic Survival Remove Ads",
    description = "Removes interstitial, app-open and banner ads: nothing is ever loaded or " +
        "displayed, and any show call resolves the game's ad callbacks instantly so the flow " +
        "never stalls.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MAGIC_SURVIVAL)

    execute {
        // ── Interstitial: never loads, never shows ──
        InterstitialLoadAdFingerprint.method.returnEarly()
        InterstitialPollAdFingerprint.method.returnEarly()
        InterstitialShowFingerprint.method.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/google/unity/ads/Interstitial;->callback:Lcom/google/unity/ads/UnityInterstitialAdCallback;
            if-eqz v0, :done
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdShowedFullScreenContent()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdImpression()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            :done
            return-void
            """.trimIndent()
        )

        // ── App open: never loads, never shows ──
        UnityAppOpenAdLoadAdFingerprint.method.returnEarly()
        UnityAppOpenAdPollAdFingerprint.method.returnEarly()
        UnityAppOpenAdShowFingerprint.method.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityAppOpenAd;->callback:Lcom/google/unity/ads/UnityAppOpenAdCallback;
            if-eqz v0, :done
            invoke-interface {v0}, Lcom/google/unity/ads/UnityAppOpenAdCallback;->onAdShowedFullScreenContent()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityAppOpenAdCallback;->onAdImpression()V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityAppOpenAdCallback;->onAdDismissedFullScreenContent()V
            :done
            return-void
            """.trimIndent()
        )

        // ── Banner: never fetched, never drawn ──
        BannerLoadAdFingerprint.method.returnEarly()
        BannerShowFingerprint.method.returnEarly()
    }
}

/**
 * Magic Survival — Instant Rewarded Rewards (v0.9941, Google Mobile Ads Unity bridge).
 *
 * The rewarded flow is made synchronous and offline-capable:
 *
 *  1. `isAdAvailable()` always true — the C# readiness gate passes with no
 *     network at all.
 *  2. `loadAd()` / `pollAd()` (preloader route) immediately report
 *     `onRewardedAdLoaded()` on the stored C# proxy instead of fetching a
 *     creative, so the "watch ad" button becomes usable instantly.
 *  3. `show()` fires, in the order the real plugin would have fired them:
 *     `onAdShowedFullScreenContent()` → `onUserEarnedReward("reward", 1.0f)`
 *     → `onAdDismissedFullScreenContent()`. The reward values match the
 *     production-verified Traffic Rider / Missiles payloads for this same
 *     bridge; games that read `Reward.Amount` get 1 unit, games that grant
 *     their own configured amount ignore them (the overwhelmingly common case).
 *  4. `getRewardItem()` answers with `RewardItem.DEFAULT_REWARD` (type "",
 *     amount 1) — the ad object is deliberately never populated, and a null
 *     RewardItem reaching the C# proxy would be an NPE.
 *
 * `UnityRewardedInterstitialAd.loadAd` is deliberately left REAL: that format
 * is interstitial-family and may be auto-shown when ready, so faking
 * readiness there risks a load→show→load feedback loop. Its `show()` is still
 * faked, so a loaded one grants instantly, and offline it simply falls back to
 * stock "not ready" behaviour.
 *
 * Register budgets (verified against 0.9941 smali):
 *   UnityRewardedAd.isAdAvailable                 .registers 3 → returnEarly
 *   UnityRewardedAd.loadAd / pollAd               .registers 5 / 9
 *   UnityRewardedAd.show                          .registers 3 → cloneMutable(+2) → 5
 *   UnityRewardedAd.getRewardItem                 .registers 7
 *   UnityRewardedInterstitialAd.show              .registers 3 → cloneMutable(+2) → 5
 *   UnityRewardedInterstitialAd.getRewardItem     .registers 7
 */
@Suppress("unused")
val magicSurvivalRewardedRewardsPatch = bytecodePatch(
    name = "Magic Survival Instant Rewarded Rewards",
    description = "Grants rewarded-ad rewards instantly on tap without watching an ad: the " +
        "rewarded ad reports ready immediately and show() fires the reward and close callbacks " +
        "synchronously.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MAGIC_SURVIVAL)

    execute {
        // ── Readiness gate always passes ──
        UnityRewardedAdIsAdAvailableFingerprint.method.returnEarly(true)

        // ── loadAd / pollAd report ready without touching the network ──
        UnityRewardedAdLoadAdFingerprint.method.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            if-eqz v0, :done
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onRewardedAdLoaded()V
            :done
            return-void
            """.trimIndent()
        )
        UnityRewardedAdPollAdFingerprint.method.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            if-eqz v0, :done
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onRewardedAdLoaded()V
            :done
            return-void
            """.trimIndent()
        )

        // ── show() grants instantly ──
        // ".registers 3" → clone +2 → 5 (v0..v3 locals + p0): v0 callback,
        // v1 reward type, v2 reward amount.
        val rewardedShow = UnityRewardedAdShowFingerprint.method.cloneMutable(additionalRegisters = 2)
        mutableClassDefBy(rewardedShow.definingClass).methods.apply {
            remove(UnityRewardedAdShowFingerprint.method)
            add(rewardedShow)
        }
        rewardedShow.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            if-eqz v0, :done
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onAdDismissedFullScreenContent()V
            :done
            return-void
            """.trimIndent()
        )

        // ── Rewarded interstitial: show path only (load stays real) ──
        val rewardedInterstitialShow = UnityRewardedInterstitialAdShowFingerprint.method
            .cloneMutable(additionalRegisters = 2)
        mutableClassDefBy(rewardedInterstitialShow.definingClass).methods.apply {
            remove(UnityRewardedInterstitialAdShowFingerprint.method)
            add(rewardedInterstitialShow)
        }
        rewardedInterstitialShow.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedInterstitialAd;->callback:Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;
            if-eqz v0, :done
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onAdShowedFullScreenContent()V
            const-string v1, "reward"
            const v2, 0x3f800000
            invoke-interface {v0, v1, v2}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V
            invoke-interface {v0}, Lcom/google/unity/ads/UnityRewardedInterstitialAdCallback;->onAdDismissedFullScreenContent()V
            :done
            return-void
            """.trimIndent()
        )

        // ── getRewardItem never returns null to C# ──
        val defaultReward = """
            sget-object v0, Lcom/google/android/gms/ads/rewarded/RewardItem;->DEFAULT_REWARD:Lcom/google/android/gms/ads/rewarded/RewardItem;
            return-object v0
        """.trimIndent()
        UnityRewardedAdGetRewardItemFingerprint.method.addInstructions(0, defaultReward)
        UnityRewardedInterstitialAdGetRewardItemFingerprint.method.addInstructions(0, defaultReward)
    }
}

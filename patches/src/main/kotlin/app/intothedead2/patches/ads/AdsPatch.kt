package app.intothedead2.patches.ads

import app.intothedead2.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD_2
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/**
 * Into the Dead 2 — ad rewards grant plus startup-ad kill (single combined patch).
 *
 * Technical notes for developers:
 *
 * Part 1 — AD REWARDS (T4, device-proved recipe). The C# ShowAd entry lands in
 * RewardedAd.showAd(placement). Availability is faked first: isAdReady returns
 * true, static isPlacementCapped returns false, and loadAd fires a synthetic
 * onAdLoaded on the stored C# listener so the game believes a video is ready.
 * showAd then fires the real listener sequence onAdDisplayed, onAdRewarded,
 * onAdClosed. The reward name and amount come from a live
 * getReward(placement) lookup on the bridge, falling back to a LevelPlayReward
 * built from the live placement string when the lookup returns null, so no
 * placement or reward-name constant is hardcoded anywhere. The C# proxy is
 * captured into a new mUnityRewardedAdListener instance field inside
 * setupRewardedListener, which runs from the bridge constructor. When the
 * field is unset the injected blocks fall through to the original bodies.
 *
 * Part 2 — STARTUP AD ONLY (T5 plus L1, L3, L2). The cold-start fullscreen ad
 * is killed at every C#-reachable show entry: LevelPlay InterstitialAd.showAd
 * returns void immediately, TapjoyConnectUnity.showPlacementContent returns
 * void, TJPlacement.showContent returns void (this is the choke point that
 * also covers the SDK-internal AppLaunch auto-show path which bypasses the
 * bridge method), and both Meta InterstitialAd.show overloads report failure.
 *
 * Deliberately out of scope: banners, UMP consent, CrossPromo WebView and
 * AlertView, MoreGames and DRSPromo popups, DailyReward popups, Offerwall,
 * and the Unity splash, all of which are lookalikes or user-invoked flows.
 *
 * Register budgets, all verified against smali: RewardedAd.loadAd has
 * .registers 2 and is expanded by 2; RewardedAd.showAd has .registers 4 and
 * is expanded by 4; setupRewardedListener needs no new registers.
 */
@Suppress("unused")
val intoTheDead2AdsPatch = bytecodePatch(
    name = "AD Instant Rewards",
    description = "Grants rewarded-video rewards instantly",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD_2)

    execute {
        // ---- Part 1a: reward availability reads as ready. ----
        RewardedAdIsAdReadyFingerprint.method.returnEarly(true)
        RewardedAdIsPlacementCappedFingerprint.method.returnEarly(false)

        // ---- Part 1b: capture the C# proxy listener for later use. ----
        mutableClassDefBy("Lcom/ironsource/unity/androidbridge/RewardedAd;").fields.add(
            ImmutableField(
                "Lcom/ironsource/unity/androidbridge/RewardedAd;",
                "mUnityRewardedAdListener",
                "Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;",
                AccessFlags.PRIVATE.value,
                null,
                null,
                null
            ).toMutable()
        )
        RewardedAdSetupListenerFingerprint.method.addInstructions(
            0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            """.trimIndent()
        )

        // ---- Part 1c: loadAd reports a loaded ad to the game. ----
        val rewardedLoadAd = RewardedAdLoadAdFingerprint.method.cloneMutable(additionalRegisters = 2)
        mutableClassDefBy(rewardedLoadAd.definingClass).methods.apply {
            remove(RewardedAdLoadAdFingerprint.method)
            add(rewardedLoadAd)
        }
        rewardedLoadAd.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            const-string v1, "{}"
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdLoaded(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
            """.trimIndent()
        )

        // ---- Part 1d: showAd grants the reward instantly, no video. ----
        // Reward lookup is live: getReward(placement) supplies the exact name
        // and amount the game expects. A null lookup falls back to a reward
        // built from the live placement string, so nothing is hardcoded.
        val rewardedShowAd = RewardedAdShowAdFingerprint.method.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(rewardedShowAd.definingClass).methods.apply {
            remove(RewardedAdShowAdFingerprint.method)
            add(rewardedShowAd)
        }
        rewardedShowAd.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            if-nez p1, :pl_ok
            const-string p1, ""
            :pl_ok
            nop
            invoke-virtual {p0, p1}, Lcom/ironsource/unity/androidbridge/RewardedAd;->getReward(Ljava/lang/String;)Lcom/unity3d/mediation/rewarded/LevelPlayReward;
            move-result-object v1
            if-nez v1, :has_reward
            new-instance v1, Lcom/unity3d/mediation/rewarded/LevelPlayReward;
            const/4 v2, 0x1
            invoke-direct {v1, p1, v2}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;-><init>(Ljava/lang/String;I)V
            :has_reward
            nop
            invoke-virtual {v1}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;->getName()Ljava/lang/String;
            move-result-object v2
            invoke-virtual {v1}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;->getAmount()I
            move-result v3
            const-string v1, "{}"
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdDisplayed(Ljava/lang/String;)V
            invoke-interface {v0, v1, v2, v3}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;I)V
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
            """.trimIndent()
        )

        // ---- Part 2: startup interstitial and fullscreen show kills. ----
        InterstitialAdShowAdFingerprint.method.returnEarly()
        TapjoyShowPlacementContentFingerprint.method.returnEarly()
        TJPlacementShowContentFingerprint.method.returnEarly()
        MetaInterstitialShowFingerprint.method.returnEarly(false)
        MetaInterstitialShowWithConfigFingerprint.method.returnEarly(false)
    }
}

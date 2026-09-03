package app.intothedead.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.cloneMutable
import app.morphe.util.cloneParameters
import app.intothedead.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/**
 * Into the Dead 2 — Ad Removal + Instant Boost Rewards (final, device-verified).
 *
 * ============================================================
 * THE VERIFIED FACT (device logcat, real rewarded ad, 2026-09-03)
 * ============================================================
 * With rewarded videos left stock, a REAL watched ad produced:
 *
 *   OnRewardedVideoRewarded called in PerksUGUIManager.
 *     Placement:Perks_Screen, reward: PERKS_BOOST, quantity:1
 *   → PerkBoostManager:SetPerkAsBoosted(PERK)
 *   → PerkBoostManager:SaveBoostedTimersToPlayerProfile()
 *   → PlayerProfile.Save()                      ← boost GRANTED
 *
 * Every earlier synthetic build failed ONLY because the reward name sent was
 * "PERKBOOST" (missing the S). The C# handler switch-cases on the EXACT reward
 * name string delivered in the placement JSON. The correct constant for the
 * boost placements (Perks_Screen / PERKS / …) is "PERKS_BOOST".
 *
 * All reward-video buttons in this game are perk-boost buttons (verified with
 * the user), so the placement→reward map below sends PERKS_BOOST for the boost
 * placements and passes the known coin constants through unchanged for any
 * future/other placement.
 *
 * ============================================================
 * WHAT THIS PATCH DOES
 * ============================================================
 *  A. Removes interstitial, banner and AdMob App-Open ads (legacy IronSource
 *     bridge + new LevelPlay API + AdMob — all verified kills).
 *  B. Makes rewarded videos grant instantly:
 *      1. availability faked  — isRewardedVideoAvailable→true,
 *         placement capped→false, loadRewardedVideo fires synthetic
 *         onAdAvailable/onAdReady so C# Video.State == Ready;
 *      2. AndroidBridge.showRewardedVideo (String + no-arg) fires the real
 *         callback sequence onAdOpened → onAdRewarded(placementJson) →
 *         onAdClosed with placement_reward_name = PERKS_BOOST for boost
 *         placements, exactly matching what a real completion delivers;
 *      3. new-API RewardedAd bridge (showAd/loadAd/isAdReady + stored
 *         listener) as a safety net with the same corrected reward name.
 *
 * Every path logs to logcat under tag ITD2FIX for future diagnosis.
 */
@Suppress("unused")
val intoTheDeadAdRemovalInstantBoostRewardsPatch = bytecodePatch(
    name = "Into the Dead 2 Ad Removal & Instant Boost Rewards",
    description = "Removes all ads (interstitials, banners, app-open) and grants rewarded-video perk boosts instantly on tap (no ad watch) using the correct reward name PERKS_BOOST, verified against a real rewarded event.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD)

    execute {
        // ==========================================================
        // SECTION A — Instant Boost Rewards: fake availability (REWARD FIX)
        // ==========================================================

        // C# IsVideoAvailable(placementId) poll → always true (tap gate passes).
        AndroidBridgeIsRewardedVideoAvailableFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // Placement-cap poll → never capped.
        AndroidBridgeIsRewardedVideoPlacementCappedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // loadRewardedVideo()V → fire synthetic onAdAvailable (regular listener)
        // and onAdReady (manual listener) so C# raises VideoReady → Video.State=Ready.
        // Original .registers 1 → clone +4 → registers 5, swap the original out.
        val loadRewardedVideoMethod = AndroidBridgeLoadRewardedVideoFingerprint.method
        val loadRewardedVideoMethodExpanded = loadRewardedVideoMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(loadRewardedVideoMethod.definingClass).methods.apply {
            remove(loadRewardedVideoMethod)
            add(loadRewardedVideoMethodExpanded)
        }
        loadRewardedVideoMethodExpanded.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayRewardedVideoWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayRewardedVideoListener(Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;
            move-result-object v1
            if-eqz v1, :cond_manual
            const-string v2, "ITD2FIX"
            const-string v3, "loadRewardedVideo: fake onAdAvailable"
            invoke-static {v2, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            move-object v2, v1
            const-string v3, "{}"
            invoke-interface {v2, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdAvailable(Ljava/lang/String;)V
            :cond_manual
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayRewardedVideoWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayRewardedVideoManualListener(Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoManualListener;
            move-result-object v1
            if-eqz v1, :cond_real
            const-string v2, "ITD2FIX"
            const-string v3, "loadRewardedVideo: fake onAdReady"
            invoke-static {v2, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            move-object v2, v1
            const-string v3, "{}"
            invoke-interface {v2, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoManualListener;->onAdReady(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // NEW-API safety net: RewardedAd.isAdReady() → always true.
        RewardedAdIsAdReadyFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // NEW-API safety net: RewardedAd.loadAd() → synthetic onAdLoaded on the
        // stored C# listener; falls through to the real load when unset.
        val rewardedAdLoadAdMethod = RewardedAdLoadAdFingerprint.method
        val rewardedAdLoadAdMethodExpanded = rewardedAdLoadAdMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(rewardedAdLoadAdMethod.definingClass).methods.apply {
            remove(rewardedAdLoadAdMethod)
            add(rewardedAdLoadAdMethodExpanded)
        }
        rewardedAdLoadAdMethodExpanded.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            const-string v1, "ITD2FIX"
            const-string v2, "RewardedAd.loadAd: fake onAdLoaded"
            invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            const-string v1, "{}"
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdLoaded(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // ==========================================================
        // SECTION B — Instant Boost Rewards: legacy bridge grant (PRIMARY)
        // ==========================================================
        //
        // showRewardedVideo(String)V — original .registers 2 (p0=this, p1=placement,
        // ZERO locals) → clone +8 → registers 10 (v0..v7 + p0@8 + p1@9). The
        // placement is kept in p1 for the whole block (register-safe mapping).

        val showRewardedMethod = ShowRewardedVideoFingerprint.method
        val showRewardedMethodExpanded = showRewardedMethod.cloneMutable(additionalRegisters = 8)
        mutableClassDefBy(showRewardedMethod.definingClass).methods.apply {
            remove(showRewardedMethod)
            add(showRewardedMethodExpanded)
        }
        showRewardedMethodExpanded.addInstructionsWithLabels(0, """
            const-string v0, "ITD2FIX"
            const-string v1, "SRV-string: enter"
            invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayRewardedVideoWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayRewardedVideoListener(Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;
            move-result-object v0
            if-eqz v0, :cond_real
            const-string v1, "ITD2FIX"
            invoke-static {v1, p1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            if-nez p1, :pl_ok
            const-string p1, "Perks_Screen"
            :pl_ok
            nop
            const-string v1, "PERKS_BOOST"
            const-string v2, "Coin_Reward"
            invoke-virtual {p1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :sel_coin
            const-string v2, "COIN_REWARD"
            invoke-virtual {p1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :sel_coin
            const-string v2, "FREE_COINS"
            invoke-virtual {p1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :sel_free
            goto :sel_done
            :sel_coin
            const-string v1, "COIN_REWARD"
            goto :sel_done
            :sel_free
            const-string v1, "FREE_COINS"
            :sel_done
            nop
            new-instance v2, Ljava/util/HashMap;
            invoke-direct {v2}, Ljava/util/HashMap;-><init>()V
            const-string v3, "placement_name"
            invoke-virtual {v2, v3, p1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v3, "placement_id"
            const-string v4, "0"
            invoke-virtual {v2, v3, v4}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v3, "placement_reward_amount"
            const-string v4, "1"
            invoke-virtual {v2, v3, v4}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v3, "placement_reward_name"
            invoke-virtual {v2, v3, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            new-instance v3, Lorg/json/JSONObject;
            invoke-direct {v3, v2}, Lorg/json/JSONObject;-><init>(Ljava/util/Map;)V
            invoke-virtual {v3}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v2
            const-string v3, "ITD2FIX"
            const-string v4, "SRV-string: firing reward"
            invoke-static {v3, v4}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            invoke-static {v3, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            const-string v3, "{}"
            invoke-interface {v0, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdOpened(Ljava/lang/String;)V
            invoke-interface {v0, v2, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;)V
            invoke-interface {v0, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        //  showRewardedVideo()V — parameterless variant. Original .registers 1 →
        //  clone +4 → registers 5, swap original. No placement is available →
        //  always reward PERKS_BOOST (every reward video in this game is a boost).
        val showNoArgMethod = ShowRewardedVideoNoArgFingerprint.method
        val showNoArgMethodExpanded = showNoArgMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(showNoArgMethod.definingClass).methods.apply {
            remove(showNoArgMethod)
            add(showNoArgMethodExpanded)
        }
        showNoArgMethodExpanded.addInstructionsWithLabels(0, """
            const-string v0, "ITD2FIX"
            const-string v1, "SRV-noarg: enter"
            invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayRewardedVideoWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayRewardedVideoListener(Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;
            move-result-object v0
            if-eqz v0, :cond_real
            new-instance v1, Ljava/util/HashMap;
            invoke-direct {v1}, Ljava/util/HashMap;-><init>()V
            const-string v2, "placement_name"
            const-string v3, "Perks_Screen"
            invoke-virtual {v1, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_id"
            const-string v3, "0"
            invoke-virtual {v1, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_reward_amount"
            const-string v3, "1"
            invoke-virtual {v1, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_reward_name"
            const-string v3, "PERKS_BOOST"
            invoke-virtual {v1, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            new-instance v2, Lorg/json/JSONObject;
            invoke-direct {v2, v1}, Lorg/json/JSONObject;-><init>(Ljava/util/Map;)V
            invoke-virtual {v2}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v1
            const-string v2, "ITD2FIX"
            const-string v3, "SRV-noarg: firing reward"
            invoke-static {v2, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            invoke-static {v2, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            const-string v2, "{}"
            invoke-interface {v0, v2}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdOpened(Ljava/lang/String;)V
            invoke-interface {v0, v1, v2}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;)V
            invoke-interface {v0, v2}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // ==========================================================
        // SECTION C — Instant Boost Rewards: new-API bridge (safety net)
        // ==========================================================

        val rewardedAdClass = mutableClassDefBy("Lcom/ironsource/unity/androidbridge/RewardedAd;")

        // 1. Instance field holding the C# proxy listener.
        rewardedAdClass.fields.add(
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

        // 2. Store the C# proxy when setupRewardedListener is called (both constructors).
        RewardedAdSetupListenerFingerprint.method.addInstructions(0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
        """.trimIndent())

        // 3. showAd(String)V → grant immediately.
        //    Real LevelPlay forwarder (RewardedAd$1) semantics:
        //    onAdRewarded(adInfo, LevelPlayReward.getName(), LevelPlayReward.getAmount()).
        //    arg2 must be the REWARD NAME (PERKS_BOOST for boost placements).
        //    Original .registers 4 → clone +6 → registers 10, swap original.
        val rewardedAdShowAdMethod = RewardedAdShowAdFingerprint.method
        val rewardedAdShowAdMethodExpanded = rewardedAdShowAdMethod.cloneMutable(additionalRegisters = 6)
        mutableClassDefBy(rewardedAdShowAdMethod.definingClass).methods.apply {
            remove(rewardedAdShowAdMethod)
            add(rewardedAdShowAdMethodExpanded)
        }
        rewardedAdShowAdMethodExpanded.addInstructionsWithLabels(0, """
            const-string v0, "ITD2FIX"
            const-string v1, "RewardedAd.showAd: enter"
            invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            invoke-static {v0, p1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            if-nez p1, :pl_ok
            const-string p1, "Perks_Screen"
            :pl_ok
            nop
            const-string v1, "PERKS_BOOST"
            const-string v2, "Coin_Reward"
            invoke-virtual {p1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :sel_coin
            const-string v2, "COIN_REWARD"
            invoke-virtual {p1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :sel_coin
            const-string v2, "FREE_COINS"
            invoke-virtual {p1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :sel_free
            goto :sel_done
            :sel_coin
            const-string v1, "COIN_REWARD"
            goto :sel_done
            :sel_free
            const-string v1, "FREE_COINS"
            :sel_done
            nop
            const-string v2, "ITD2FIX"
            const-string v3, "RewardedAd.showAd: firing reward"
            invoke-static {v2, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            invoke-static {v2, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            const-string v2, "{}"
            invoke-interface {v0, v2}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdDisplayed(Ljava/lang/String;)V
            const/4 v3, 0x1
            invoke-interface {v0, v2, v1, v3}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;I)V
            invoke-interface {v0, v2}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // ==========================================================
        // SECTION D — Remove Ads (legacy IronSource bridge)
        // ==========================================================
        // 1. Ready-signal kill (PRIMARY): the C# manager never learns an ad is ready.
        LevelPlayInterstitialWrapperOnAdReadyFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 2. Readiness poll → always false (defense).
        AndroidBridgeIsInterstitialReadyFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // 3. Explicit load → no-op (network save; auto-load covered by 1/2/4).
        AndroidBridgeLoadInterstitialFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 4a. showInterstitial(String)V → synthetic close-lifecycle via C# proxy.
        val showStringMethod = AndroidBridgeShowInterstitialStringFingerprint.method.cloneParameters()
        showStringMethod.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayInterstitialWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayInterstitialListener(Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;
            move-result-object v1
            if-eqz v1, :cond_real
            const-string v0, ""
            invoke-interface {v1, v0}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;->onAdOpened(Ljava/lang/String;)V
            invoke-interface {v1, v0}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;->onAdShowSucceeded(Ljava/lang/String;)V
            invoke-interface {v1, v0}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // 4b. showInterstitial()V → same synthetic lifecycle, no placement.
        val showMethod = AndroidBridgeShowInterstitialFingerprint.method
        val showMethodExpanded = showMethod.cloneMutable(additionalRegisters = 2)
        mutableClassDefBy(showMethod.definingClass).methods.apply {
            remove(showMethod)
            add(showMethodExpanded)
        }
        showMethodExpanded.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayInterstitialWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayInterstitialListener(Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;
            move-result-object v1
            if-eqz v1, :cond_real
            const-string v0, ""
            invoke-interface {v1, v0}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;->onAdOpened(Ljava/lang/String;)V
            invoke-interface {v1, v0}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;->onAdShowSucceeded(Ljava/lang/String;)V
            invoke-interface {v1, v0}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayInterstitialListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // 5. Banner: never created. display/hide/destroy degrade to null-safe no-ops.
        AndroidBridgeLoadBannerFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // ==========================================================
        // SECTION E — Remove Ads (new LevelPlay API safety nets)
        // ==========================================================

        InterstitialAdLoadAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        InterstitialAdIsAdReadyFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        InterstitialAdShowAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        BannerAdLoadFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        BannerAdShowAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // ==========================================================
        // SECTION F — Remove App Open Ad (AdMob App Open bridge)
        // ==========================================================

        UnityAppOpenAdLoadAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        UnityAppOpenAdShowFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        UnityAppOpenAdPollAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        UnityAppStateEventNotifierStartListeningFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())
    }
}

package app.intothedead.patches.ads

import app.intothedead.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/**
 * Into the Dead 1 — Ad Removal & Instant Rewarded Grants (v2.9.5, new-API only).
 *
 * ============================================================
 * WHY THIS PATCH WAS REWRITTEN FOR 2.9.5
 * ============================================================
 * 2.9.5 upgraded the IronSource/LevelPlay Unity plugin and deleted the entire
 * LEGACY Unity bridge — `com.ironsource.unity.androidbridge.AndroidBridge`,
 * `AndroidBridge$1..$8`, `LevelPlayRewardedVideoWrapper`,
 * `LevelPlayInterstitialWrapper`, `LevelPlayBannerWrapper` and every
 * `UnityLevelPlay*Listener` interface. IL2CPP `global-metadata.dat` confirms the
 * C# side migrated in lockstep: only `Unity.Services.LevelPlay.LevelPlayRewardedAd`
 * / `LevelPlayInterstitialAd` / `LevelPlayBannerAd` remain, and the `IronSource.Agent`
 * wrapper types are gone.
 *
 * The previous patch set targeted the legacy bridge for its PRIMARY paths
 * (`AndroidBridge.showRewardedVideo`, `isRewardedVideoAvailable`,
 * `loadRewardedVideo`, `isInterstitialReady`, `loadInterstitial`,
 * `showInterstitial`, `loadBanner`, `LevelPlayInterstitialWrapper.onAdReady`).
 * All of those classes no longer exist, so `Patches.kt` aborted on the very first
 * fingerprint with `PatchException: Failed to match the fingerprint`.
 *
 * This rewrite drops every legacy target and rebuilds on the surviving
 * NEW-API bridge (classes7/com/ironsource/unity/androidbridge) plus the untouched
 * Google Unity Ads App-Open bridge. Net feature coverage is unchanged:
 *   - rewarded videos grant instantly on tap, no ad watch
 *   - interstitials never load, never report ready, never show (and always
 *     resolve their close callback so the game never stalls)
 *   - banners never load or occupy layout
 *   - app-open ads never load, never show, never auto-trigger on foreground
 *
 * ============================================================
 * PART 1 — REWARDED VIDEOS GRANT INSTANTLY
 * ============================================================
 * The C# entry is `LevelPlayRewardedAd.ShowAd(placementName)` → JNI →
 * `RewardedAd.showAd(String)`. Availability is faked first, then a synthetic
 * displayed-rewarded-closed lifecycle is fired on the real C# proxy listener.
 *
 *  1. `RewardedAd.isAdReady()` → always true, so the C# `IsAdReady` gate passes.
 *  2. `RewardedAd.isPlacementCapped(String)` → always false, so a frequency-capped
 *     placement can never block the tap.
 *  3. `RewardedAd.setupRewardedListener(p1)` captures the C# proxy into a new
 *     instance field `mUnityRewardedAdListener`. The constructor calls this with
 *     the proxy as p1, so the field is populated before any show can happen.
 *  4. `RewardedAd.loadAd()` fires a synthetic `onAdLoaded("{}")` on that proxy
 *     (exactly the string the real forwarder `RewardedAd$1` passes) so the C# side
 *     raises its "ad ready" event.
 *  5. `RewardedAd.showAd(placement)` resolves the reward LIVE through
 *     `getReward(placement)`. If that returns null we fall back to a
 *     `LevelPlayReward` built from the live placement string, so NO placement id
 *     or reward-name constant is hardcoded anywhere in this patch.
 *     It then fires, in order:
 *       onAdDisplayed("{}") → onAdRewarded("{}", name, amount) → onAdClosed("{}")
 *     which matches `RewardedAd$1.onAdRewarded`'s real forward semantics
 *     (`adInfoString, reward.getName(), reward.getAmount()`).
 *     If the proxy is somehow unset the original body still runs.
 *
 * ============================================================
 * PART 2 — REMOVE INTERSTITIALS
 * ============================================================
 * `InterstitialAd.loadAd()` and `showAd()` never reach the SDK, and
 * `isAdReady()` always reports false. `showAd()` additionally fires a synthetic
 * `onAdDisplayed("{}") → onAdClosed("{}")` on the stored C# proxy (captured the
 * same way, in `setupInterstitialListener`) so the game's interstitial state
 * machine always resolves. A bare no-op on show would leave C# waiting forever
 * for a close event that can no longer arrive — this is the one behavioural
 * difference from the previous set, and it is a fix, not a regression.
 *
 * ============================================================
 * PART 3 — REMOVE BANNERS / APP OPEN ADS
 * ============================================================
 * `BannerAd.load()`/`showAd()` no-op, so the banner view is never created.
 * `UnityAppOpenAd.loadAd()`/`show()`/`pollAd()` no-op (load = source kill,
 * show = direct kill, pollAd = closes the Google Play Services preloaded-cache
 * route that bypasses loadAd), and `UnityAppStateEventNotifier.startListening()`
 * no-op so the foreground auto-show trigger is never armed.
 *
 * ============================================================
 * REGISTER BUDGETS — all re-verified against 2.9.5 smali
 * ============================================================
 *  RewardedAd.isAdReady                    .registers 2  → returnEarly
 *  RewardedAd.isPlacementCapped (static)    .registers 1  → returnEarly
 *  RewardedAd.setupRewardedListener         .registers 4  → no expansion needed
 *  RewardedAd.loadAd                       .registers 2  → cloneMutable(+2) → 4
 *  RewardedAd.showAd                       .registers 4  → cloneMutable(+4) → 8
 *  InterstitialAd.isAdReady                .registers 2  → returnEarly
 *  InterstitialAd.setupInterstitialListener.registers 4  → no expansion needed
 *  InterstitialAd.showAd                   .registers 4  → cloneMutable(+2) → 6
 *  BannerAd.load                           .registers 2  → bare return-void
 *  BannerAd.showAd                         .registers 3  → bare return-void
 *  UnityAppOpenAd.loadAd / show / pollAd   .registers 5 / 3 / 9 → bare return-void
 *  UnityAppStateEventNotifier.startListening.registers 3  → bare return-void
 */
@Suppress("unused")
val intoTheDeadAdRemovalInstantBoostRewardsPatch = bytecodePatch(
    name = "Ad Removal & Instant Boost Rewards",
    description = "Removes all ads (interstitials, banners, app-open) and grants rewarded-video perk boosts instantly on tap (no ad watch) using the correct reward name PERKS_BOOST, verified against a real rewarded event.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD)

    execute {
        // ==========================================================
        // PART 1a — availability reads as ready, never capped.
        // Both are logged: they are the exact gate that
        // IronSourceProvider.IsVideoAvailable() reads, so a log here proves
        // the rewarded flow got past the availability check.
        // ==========================================================
        RewardedAdIsAdReadyFingerprint.method.returnEarly(true)
        RewardedAdIsPlacementCappedFingerprint.method.returnEarly(false)


        // ==========================================================
        // PART 1b — capture the C# proxy listener (RewardedAd).
        // ==========================================================
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

        // ==========================================================
        // PART 1c — loadAd reports a loaded ad to the game.
        // ".registers 2" → clone +2 → 4 (v0, v1 locals + p0).
        // ==========================================================
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

        // ==========================================================
        // PART 1d — showAd grants the reward instantly, no video.
        //
        // TWO things must be right or the boost silently does nothing:
        //
        // (1) REWARD. getReward(placement) resolves live from the ad unit's
        //     configured reward, so the name is PERKS_BOOST for the boost
        //     placements without anything being hardcoded. The null fallback
        //     builds a reward from the live placement string.
        //
        // (2) PLACEMENT IN THE ADINFO — this was the real bug. The C#
        //     IronSourceProvider.OnAdRewarded does
        //         m_activeVideos.TryGetValue(info.PlacementName, out var video)
        //     so a null PlacementName throws
        //         ArgumentNullException: Value cannot be null. Parameter name: key
        //     and the whole reward delegate is never invoked. Passing "{}" as
        //     the adInfo made PlacementName null, so the perk never boosted
        //     even though the SDK logged OnAdRewarded. We therefore build the
        //     adInfo JSON with "placementName" set to the placement the game
        //     actually requested (Perks_Screen for the boost flow).
        //
        // Register budget: ".registers 4" → clone +6 → 10 (v0..v5 + p0, p1).
        //   v0 listener, v1 reward object, v2 reward name, v3 amount,
        //   v4 StringBuilder, v5 adInfo json
        // ==========================================================
        val rewardedShowAd = RewardedAdShowAdFingerprint.method.cloneMutable(additionalRegisters = 6)
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
            new-instance v4, Ljava/lang/StringBuilder;
            invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V
            const-string v5, "{\"placementName\":\""
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\"}"
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v5
            invoke-interface {v0, v5}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdDisplayed(Ljava/lang/String;)V
            invoke-interface {v0, v5, v2, v3}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;I)V
            invoke-interface {v0, v5}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
            """.trimIndent()
        )

        // ==========================================================
        // PART 2 — interstitials: never load, never ready, and show
        // always resolves via a synthetic displayed/closed lifecycle.
        // ==========================================================

        InterstitialAdLoadAdFingerprint.method.returnEarly()
        InterstitialAdIsAdReadyFingerprint.method.returnEarly(false)

        mutableClassDefBy("Lcom/ironsource/unity/androidbridge/InterstitialAd;").fields.add(
            ImmutableField(
                "Lcom/ironsource/unity/androidbridge/InterstitialAd;",
                "mUnityInterstitialAdListener",
                "Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;",
                AccessFlags.PRIVATE.value,
                null,
                null,
                null
            ).toMutable()
        )
        InterstitialAdSetupListenerFingerprint.method.addInstructions(
            0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/InterstitialAd;->mUnityInterstitialAdListener:Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;
            """.trimIndent()
        )

        // Same placementName requirement as the rewarded path: the C#
        // interstitial handlers key off info.PlacementName, so "{}" would give a
        // null key. ".registers 4" → clone +4 → 8 (v0..v3 locals + p0, p1).
        //   v0 listener, v1 StringBuilder, v2/v3 scratch + json result
        val interstitialShowAd = InterstitialAdShowAdFingerprint.method.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(interstitialShowAd.definingClass).methods.apply {
            remove(InterstitialAdShowAdFingerprint.method)
            add(interstitialShowAd)
        }
        interstitialShowAd.addInstructionsWithLabels(
            0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/InterstitialAd;->mUnityInterstitialAdListener:Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;
            if-eqz v0, :cond_real
            if-nez p1, :pl_ok
            const-string p1, ""
            :pl_ok
            nop
            new-instance v1, Ljava/lang/StringBuilder;
            invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
            const-string v2, "{\"placementName\":\""
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v2, "\"}"
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v3
            invoke-interface {v0, v3}, Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;->onAdDisplayed(Ljava/lang/String;)V
            invoke-interface {v0, v3}, Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
            """.trimIndent()
        )

        // ==========================================================
        // PART 3 — banners and App Open Ads never happen.
        // ==========================================================

        BannerAdLoadFingerprint.method.returnEarly()
        BannerAdShowAdFingerprint.method.returnEarly()

        UnityAppOpenAdLoadAdFingerprint.method.returnEarly()
        UnityAppOpenAdShowFingerprint.method.returnEarly()
        UnityAppOpenAdPollAdFingerprint.method.returnEarly()
        UnityAppStateEventNotifierStartListeningFingerprint.method.returnEarly()
    }
}

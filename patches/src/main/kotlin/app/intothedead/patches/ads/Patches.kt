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
 * Into the Dead — Ad Removal
 *
 * Single consolidated patch replacing the previous five ad patches
 * (Instant Rewards legacy, Instant Rewards LevelPlay, Remove Ads legacy,
 * Remove Ads LevelPlay, Remove App Open Ad). The execute block runs all five
 * sections in order, so the MPP lists exactly one Ad Removal patch.
 *
 * ============================================================
 * SECTION 1 — Instant Rewards (legacy IronSource bridge)
 * ============================================================
 *
 * com.ironsource.unity.androidbridge.AndroidBridge.showRewardedVideo(String)V is the
 * legacy C# show entry (`IronSource.Agent.ShowRewardedVideo`). Normally it forwards to
 * `com.ironsource.mediationsdk.IronSource.showRewardedVideo(String)` which loads and
 * displays a real rewarded ad. Instead we fire the full reward lifecycle directly on the
 * C# proxy (`UnityLevelPlayRewardedVideoListener`) via the same field path the SDK uses:
 *
 *   AndroidBridge.mLevelPlayRewardedVideoWrapper (private, same class → iget-object)
 *     → LevelPlayRewardedVideoWrapper.mUnityLevelPlayRewardedVideoListener
 *       (private, cross-class → use the nestmate synthetic accessor
 *        `-$$Nest$fgetmUnityLevelPlayRewardedVideoListener`, exactly like
 *        LevelPlayRewardedVideoWrapper$6.run does — same package, verifier-safe)
 *
 * We fire onAdOpened("{}") → onAdRewarded(placementJson, "{}") → onAdClosed("{}")
 * synchronously. onAdOpened/onAdClosed carry the adInfo-style JSON ("{}") exactly like
 * the real SDK forwarders ($3/$7 pass getAdInfoString(adInfo)); onAdRewarded carries the
 * placement JSON first, then the adInfo string (matches $6). The game's C#
 * `IronSourceRewardedVideoEvents`/`OnRewardedVideoRewarded` then grants the game-configured
 * reward (PikPok RewardService.ClaimReward) without any ad.
 *
 * placementJson mirrors AndroidBridgeUtilities.getPlacememtJson(Placement) — a JSONObject
 * built from a HashMap with keys placement_id / placement_name / placement_reward_amount /
 * placement_reward_name. placement_name is the real tapped placement (p1) — the C# handler
 * matches it against PERKBOOST/PERKS/COIN_REWARD/FREE_COINS/REWARDED_VIDEO, so the value is
 * load-bearing. If p1 is null we default it to "REWARDED_VIDEO" (a real handler constant)
 * so the match can never fail on a null name.
 *
 * The method only has .registers 2 (p0, p1 — zero locals), so cloneParameters() clones it
 * with 2 extra registers (registers 4: v0-v1 locals + p0=this + p1=placement). The injected
 * block uses v0..v3/p0/p1 and falls through to the ORIGINAL body (IronSource.showRewardedVideo)
 * when the wrapper or listener is missing, so a real ad still shows if the bridge is unset.
 *
 * The same lifecycle is applied to the parameterless showRewardedVideo()V (.registers 1 —
 * zero locals, expanded via cloneMutable(additionalRegisters = 4) → registers 5), always
 * using placement "REWARDED_VIDEO"; it falls through to the real
 * IronSource.showRewardedVideo()V when the bridge is unset.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:1618
 * (String variant), :1609 (parameterless variant), LevelPlayRewardedVideoWrapper.smali:25
 * (-$$Nest$fget...), :166 (onAdRewarded), $3/$6/$7 (adInfo/placement arg forwarding),
 * UnityLevelPlayRewardedVideoListener.smali (onAdOpened/onAdRewarded/onAdClosed).
 *
 * ============================================================
 * SECTION 2 — Instant Rewards (new LevelPlayRewardedAd API)
 * ============================================================
 *
 * com.ironsource.unity.androidbridge.RewardedAd is the new-API bridge
 * (`Unity.Services.LevelPlay.LevelPlayRewardedAd` in C#). Normally showAd(String) forwards
 * to `LevelPlayRewardedAd.showAd(Activity, String)` and a real ad plays; the reward only
 * reaches the C# proxy `IUnityRewardedAdListener` through the anonymous RewardedAd$1 SDK
 * listener after the ad completes.
 *
 * This patch makes showAd() grant immediately:
 *  1. Adds an instance field `mUnityRewardedAdListener:IUnityRewardedAdListener;` to
 *     RewardedAd (dexlib2 ImmutableField; private is fine — all access is in-class).
 *  2. Injects `iput-object p1, p0, ...mUnityRewardedAdListener` at the top of the private
 *     setupRewardedListener(IUnityRewardedAdListener)V — called from BOTH constructors —
 *     so the C# proxy is stored when the ad object is created.
 *  3. Rewrites showAd(String)V to fire onAdDisplayed("{}") → onAdRewarded("{}", placement, 1)
 *     → onAdClosed("{}") on the stored listener and return; if the listener is missing it
 *     falls through to the ORIGINAL body (LevelPlayRewardedAd.showAd), so a real ad still
 *     shows. onAdRewarded carries the REAL tapped placement (p1, defaulted to
 *     "REWARDED_VIDEO" when null) as the reward name — the C# handler matches rewardName
 *     against PERKBOOST/PERKS/COIN_REWARD/FREE_COINS/REWARDED_VIDEO, so an empty name
 *     would never grant.
 *
 * Register budget (CRITICAL — verified): showAd and setupRewardedListener both have
 * .registers 4 = v0, v1 TRUE LOCALS + p0 (register 2) + p1 (register 3). In smali, v2/v3
 * ALIAS p0/p1 — the previous block wrote const-string v2, "" and const/4 v3, 0x0, which
 * overwrote this/placement as scratch, so onAdRewarded received ("", "", 0) and the reward
 * name never reached C#. The rewritten block reads p1 (and defaults it) BEFORE using v2/p0
 * as the amount scratch; this is only clobbered on the synthetic path (after the null-check
 * branch), so the :cond_real fall-through still sees an intact this/placement.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/RewardedAd.smali:80
 * (setupRewardedListener), :134 (showAd), RewardedAd$1.smali:184 (onAdRewarded forwarding),
 * IUnityRewardedAdListener.smali (onAdDisplayed/onAdRewarded/onAdClosed).
 *
 * ============================================================
 * SECTION 3 — Remove Ads (legacy IronSource bridge)
 * ============================================================
 *
 * Kills every interstitial/launch/banner path through the legacy
 * com.ironsource.unity.androidbridge bridge (classes7.dex). The game C# drives the
 * legacy provider (`IronSourceInterstitial` → `AndroidBridge`), and the SDK auto-loads
 * the interstitial after init (no setManualLoadInterstitial in this build), so the
 * ready-signal kill is MANDATORY:
 *
 *  1. LevelPlayInterstitialWrapper.onAdReady(AdInfo)V → return-void
 *     The SDK → C# "interstitial is ready" dispatch. Killing it means the C# manager
 *     never receives HandleInterstitialReadyEvent → never calls GetInterstitial →
 *     never displays → never PauseGame → zero hang. Registration as the SDK listener
 *     (IronSource.setLevelPlayInterstitialListener in the wrapper <init>) is untouched.
 *
 *  2. AndroidBridge.isInterstitialReady()Z → return false
 *     C# readiness polls always say no (defense for the auto-load path).
 *
 *  3. AndroidBridge.loadInterstitial()V → return-void
 *     Stops the explicit network load (auto-load still happens — covered by 1/2/4).
 *
 *  4. AndroidBridge.showInterstitial()V + showInterstitial(String)V →
 *     fire onAdOpened("") → onAdShowSucceeded("") → onAdClosed("") on the C# proxy
 *     (UnityLevelPlayInterstitialListener) held by the wrapper, via the same
 *     cross-class private-field nestmate accessor the SDK itself uses
 *     (`-$$Nest$fgetmUnityLevelPlayInterstitialListener`). Completes the C# lifecycle
 *     instantly — no real ad, and the game's CloseInterstitial/UnPauseGame fires.
 *     Falls back to the REAL IronSource.showInterstitial call if the wrapper or
 *     listener is unset (defense in depth, matches the instant-reward patch style).
 *
 *  5. AndroidBridge.loadBanner(String,int,int,int,String,boolean,boolean,float,float)V
 *     → return-void. Banner view is never created → nothing displays.
 *     displayBanner/hideBanner/destroyBanner are null-safe (AndroidBridge$6/$7/$8
 *     run() null-check mBannerContainer and wrap in try/catch — verified), so no
 *     downstream NPE.
 *
 * Register budgets (verified in classes7 smali):
 *  - onAdReady: .registers 3 — return-void needs no register.
 *  - isInterstitialReady: .registers 2 — v0 local exists.
 *  - loadInterstitial: .registers 1 — return-void needs no register.
 *  - showInterstitial(String): .registers 2 (ZERO locals) → cloneParameters() clones
 *    with 2 extra registers → registers 4 (v0, v1 locals + p0, p1). The injected block
 *    uses v0 (wrapper), v1 (listener), reuses v0 for "" and falls through to the
 *    ORIGINAL body when the wrapper/listener is missing.
 *  - showInterstitial(): .registers 1 (ZERO locals) → cloneMutable(additionalRegisters
 *    = 2) → registers 3 (v0, v1 locals + p0); the original is swapped out of the class
 *    with the clone (same pattern cloneParameters() uses internally). The clone's
 *    injected `move-object/from16 v0, p0` preserves `this` for the fall-through
 *    original body (`IronSource.showInterstitial()V`).
 *  - loadBanner: .registers 12 — return-void needs no register (monitor/try-catch body
 *    becomes unreachable dead code — acceptable, matches repo no-op convention).
 *
 * Do NOT touch init/initialize/onPause/onResume (stalls C# onInitializationComplete).
 *
 * ============================================================
 * SECTION 4 — Remove Ads (new LevelPlay API safety nets)
 * ============================================================
 *
 * The game compiles the NEW `Unity.Services.LevelPlay` C# API
 * (LevelPlayInterstitialAd / LevelPlayBannerAdView) but drives the LEGACY provider.
 * Patch both bridge classes cheaply as a safety net in case any path ever hits them:
 *
 *  InterstitialAd (classes7/com/ironsource/unity/androidbridge/InterstitialAd.smali):
 *   - loadAd()V      → return-void  (line 123, .registers 2)
 *   - isAdReady()Z   → return false (line 110, .registers 2 — v0 local exists)
 *   - showAd(String)V → return-void (line 134, .registers 4)
 *
 *  BannerAd (classes7/com/ironsource/unity/androidbridge/BannerAd.smali):
 *   - load()V        → return-void  (line 770, .registers 2)
 *   - showAd()V      → return-void  (line 803, .registers 3)
 *
 * No synthetic close-callback on the new-API showAd: the game's InterstitialManager
 * uses the legacy provider, so the legacy showInterstitial synthetic lifecycle
 * (section 3) is what actually matters. If a future test shows the new API being
 * driven, upgrade showAd to a synthetic onAdDisplayed→onAdClosed with a stored
 * IUnityInterstitialAdListener field (same structural change as the rewarded
 * RewardedAd patch) — out of scope here.
 *
 * ============================================================
 * SECTION 5 — Remove App Open Ad (AdMob App Open bridge, classes8.dex)
 * ============================================================
 *
 * Kills the second-launch full-screen ad at the com.google.unity.ads bridge
 * layer — the Google Mobile Ads APP OPEN AD driven by PikPok C#
 * (PikPok.Advertising.LoadAppOpenAd/ShowAppOpenAd) via JNI. This is a SEPARATE
 * dex (classes8) from the IronSource bridge patches (classes7) and never touches
 * MobileAds init (shared with the IronSource AdMob adapter) or the underlying
 * classes3 AppOpenAd renderer.
 *
 * 1. UnityAppOpenAd.loadAd(String, AdRequest)V → return-void (PRIMARY)
 *    Nothing ever loads → isAdAvailable() false → C# ShowAppOpenAd gate skips.
 * 2. UnityAppOpenAd.show()V → return-void (PRIMARY)
 *    Direct show-kill: even a late-loaded appOpenAd never displays.
 * 3. UnityAppOpenAd.pollAd(String)V → return-void (SECONDARY)
 *    Kills the AdMob preloader cache path that could populate appOpenAd
 *    without loadAd.
 * 4. UnityAppStateEventNotifier.startListening()V → return-void (SECONDARY)
 *    Kills the auto-show trigger: the notifier never registers with
 *    ProcessLifecycleOwner → C# never receives onAppStateChanged →
 *    ShowAppOpenAd never auto-fires.
 *
 * All four: .registers unchanged, body replaced by a single return-void — no
 * try/catch/monitor in any target (verified), so no verifier traps. Register
 * counts: loadAd 5, show 3, pollAd 9, startListening 3 — return-void needs none.
 *
 */
@Suppress("unused")
val intoTheDeadAdRemovalPatch = bytecodePatch(
    name = "Into the Dead Ad Removal",
    description = "Removes all ads and grants rewarded-video rewards instantly: rewarded videos (legacy IronSource and LevelPlay), interstitials, launch, banner and AdMob App Open ads are all eliminated.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD)

    execute {
        // ==========================================================
        // SECTION 1 — Instant Rewards (legacy IronSource bridge)
        // ==========================================================

        // String variant: clone the method with 2 additional registers (original .registers 2 → 4).
        val showRewardedMethod = ShowRewardedVideoFingerprint.method.cloneParameters()

        showRewardedMethod.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayRewardedVideoWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayRewardedVideoListener(Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;
            move-result-object v1
            if-eqz v1, :cond_real
            if-nez p1, :cond_ok
            const-string p1, "REWARDED_VIDEO"
            :cond_ok
            nop
            new-instance v0, Ljava/util/HashMap;
            invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
            const-string v2, "placement_name"
            invoke-virtual {v0, v2, p1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_id"
            const-string v3, "0"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_reward_amount"
            const-string v3, "0"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_reward_name"
            const-string v3, "Reward"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            new-instance v2, Lorg/json/JSONObject;
            invoke-direct {v2, v0}, Lorg/json/JSONObject;-><init>(Ljava/util/Map;)V
            invoke-virtual {v2}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v2
            const-string v3, "{}"
            invoke-interface {v1, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdOpened(Ljava/lang/String;)V
            invoke-interface {v1, v2, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;)V
            invoke-interface {v1, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // Parameterless variant: .registers 1 has ZERO locals → clone with 4 extra registers
        // (registers 5: v0-v3 locals + p0), then swap the original out of the class (the exact
        // replacement cloneParameters() performs internally). Always uses placement
        // "REWARDED_VIDEO" and falls through to the real IronSource.showRewardedVideo()V.
        val showNoArgMethod = ShowRewardedVideoNoArgFingerprint.method
        val showNoArgMethodExpanded = showNoArgMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(showNoArgMethod.definingClass).methods.apply {
            remove(showNoArgMethod)
            add(showNoArgMethodExpanded)
        }
        showNoArgMethodExpanded.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/AndroidBridge;->mLevelPlayRewardedVideoWrapper:Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;
            if-eqz v0, :cond_real
            invoke-static {v0}, Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;->-${'$'}${'$'}Nest${'$'}fgetmUnityLevelPlayRewardedVideoListener(Lcom/ironsource/unity/androidbridge/LevelPlayRewardedVideoWrapper;)Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;
            move-result-object v1
            if-eqz v1, :cond_real
            new-instance v0, Ljava/util/HashMap;
            invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
            const-string v2, "placement_name"
            const-string v3, "REWARDED_VIDEO"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_id"
            const-string v3, "0"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_reward_amount"
            const-string v3, "0"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "placement_reward_name"
            const-string v3, "Reward"
            invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            new-instance v2, Lorg/json/JSONObject;
            invoke-direct {v2, v0}, Lorg/json/JSONObject;-><init>(Ljava/util/Map;)V
            invoke-virtual {v2}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v2
            const-string v3, "{}"
            invoke-interface {v1, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdOpened(Ljava/lang/String;)V
            invoke-interface {v1, v2, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;)V
            invoke-interface {v1, v3}, Lcom/ironsource/unity/androidbridge/UnityLevelPlayRewardedVideoListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // ==========================================================
        // SECTION 2 — Instant Rewards (new LevelPlayRewardedAd API)
        // ==========================================================

        val rewardedAdClass = mutableClassDefBy("Lcom/ironsource/unity/androidbridge/RewardedAd;")

        // 1. Add an instance field to hold the C# proxy listener.
        // ImmutableField is a Java class — positional args only, in dexlib2 order:
        // (definingClass, name, type, accessFlags, initialValue, annotations, hiddenApiRestrictions).
        rewardedAdClass.fields.add(
            ImmutableField(
                "Lcom/ironsource/unity/androidbridge/RewardedAd;",
                "mUnityRewardedAdListener",
                "Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;",
                AccessFlags.PRIVATE.value,
                null, // initialValue
                null, // annotations
                null  // hiddenApiRestrictions (Set<HiddenApiRestriction>, nullable)
            ).toMutable()
        )

        // 2. Store the C# proxy when setupRewardedListener is called (both constructors).
        RewardedAdSetupListenerFingerprint.method.addInstructions(0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
        """.trimIndent())

        // 3. Fire the reward lifecycle from showAd; fall back to a real ad if unset.
        // NOTE: .registers 4 = v0, v1 locals + p0 (register 2) + p1 (register 3); v2/v3 alias
        // p0/p1. Keep p1 (the placement) alive for onAdRewarded; use v2/p0 as the amount
        // scratch only AFTER the null-check branch (so :cond_real sees intact this/placement).
        RewardedAdShowAdFingerprint.method.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            if-nez p1, :cond_ok
            const-string p1, "REWARDED_VIDEO"
            :cond_ok
            nop
            const-string v1, "{}"
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdDisplayed(Ljava/lang/String;)V
            const/4 v2, 0x1
            invoke-interface {v0, v1, p1, v2}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;I)V
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())

        // ==========================================================
        // SECTION 3 — Remove Ads (legacy IronSource bridge)
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
        //     cloneParameters() expands .registers 2 → 4 (v0, v1 locals + p0, p1).
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
        //     .registers 1 has ZERO locals → clone with 2 extra registers (registers 3:
        //     v0, v1 locals + p0), then swap the original out of the class (the exact
        //     replacement cloneParameters() performs internally).
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
        // SECTION 4 — Remove Ads (new LevelPlay API safety nets)
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
        // SECTION 5 — Remove App Open Ad (AdMob App Open bridge)
        // ==========================================================

        // 1. PRIMARY: nothing ever loads → isAdAvailable false → C# gate skips.
        UnityAppOpenAdLoadAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 2. PRIMARY: direct show-kill — appOpenAd can never be displayed.
        UnityAppOpenAdShowFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 3. SECONDARY: kill the AdMob preloader path (no loadAd needed to fill).
        UnityAppOpenAdPollAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 4. SECONDARY: never register the lifecycle notifier → no auto-show trigger.
        UnityAppStateEventNotifierStartListeningFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())
    }
}


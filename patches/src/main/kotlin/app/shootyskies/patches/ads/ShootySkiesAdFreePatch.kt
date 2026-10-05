package app.shootyskies.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import app.shootyskies.patches.shared.Constants.COMPATIBILITY_SHOOTYSKIES

/**
 * Shooty Skies — Ad-Free (Ads blocked + instant rewards).
 *
 * Hooks the Unity→MAS bridge com.yodo1.mas.UnityYodo1Mas (classes19, R8-stable
 * — Unity C# calls it reflectively by exact name; every signature below appears
 * verbatim in global-metadata.dat). All bodies verified in smali first.
 *
 * Per-format behavior:
 *  - REWARDED: isRewardedAdLoadedV2() → true (game always believes an ad is
 *    cached); loadRewardAdV2 emits a fake 1003 LOADED instead of fetching;
 *    showRewardAdV2 synchronously emits 2001 REWARD_EARNED → 1002 CLOSED
 *    through the SDK's own event channel, so C# grants the reward with ZERO
 *    network traffic and no video. A plain no-op here would break
 *    EarnCoinsOffer / ads.rewardVideoWatched and could deadlock the UI.
 *  - INTERSTITIAL: is*Loaded → true; load emits 1003; show emits 1002 CLOSED
 *    immediately → no pause/hang, no ad.
 *  - APP-OPEN: isAppOpenAdLoaded → true; load emits 1003; show emits 1002 only.
 *  - BANNER / NATIVE: load*, show*, hide*, destroy* → pure no-op (return-void);
 *    they grant nothing and C# never blocks on them.
 *  - SDK backstop: Yodo1MasFullScreenAd.showAd → return-void, cutting any
 *    remaining SDK-internal show path (rewards never route through it).
 *
 * Event dispatch — exactly the recipe in ads.md (the channel the SDK's own
 * UnityYodo1Mas$*$1 listeners use):
 *   new Yodo1MasAdEvent(code, AdType) → UnityYodo1Mas.access$000() (gameObject)
 *   → access$100() (methodName) → com.yodo1.mas.b.a(event, 1, gameObject,
 *   methodName) → getJSONObject().toString() → access$300 → private
 *   sendMessage → UnityPlayer.UnitySendMessage. Prefer b.a over a direct
 *   sendMessage call (public vs private — how the SDK itself does it); we
 *   never touch sendMessage itself.
 *
 * Codes (verified in UnityYodo1Mas$7$1/$10$1 + Yodo1MasAdEvent):
 *   0x3eb = 1003 LOADED, 0x3ea = 1002 CLOSED, 0x7d1 = 2001 REWARD_EARNED.
 * AdType fields (Yodo1Mas$AdType.<clinit>): Reward, Interstitial, AppOpen.
 *
 * Register budget: the six event targets are `.registers 3` statics
 * (p0=Activity@v1, p1=String@v2 → only v0 local). Event blocks use v0..v3,
 * so each is expanded by `cloneMutable(additionalRegisters = 4)` →
 * registers 7 (v0..v4 locals, params moved to v5/v6) and swapped into the
 * class — no param register is ever clobbered. The is*Loaded targets are
 * `.registers 1` — returnEarly(true) emits `const/4 v0, 0x1; return v0`
 * register-free. The void targets get `return-void` at index 0; their original
 * bodies stay below as unreachable dead code.
 */
@Suppress("unused")
val shootySkiesAdFreePatch = bytecodePatch(
    name = "Shooty Skies Ad-Free (Ads blocked + instant rewards)",
    description = "Blocks banner, interstitial, app-open and native ads, and turns rewarded videos into instant rewards — nothing is ever fetched or displayed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SHOOTYSKIES)

    execute {
        // ---- Readiness polls: always "ready" ----------------------------------
        IsRewardedAdLoadedV2Fingerprint.method.returnEarly(true)
        IsInterstitialAdLoadedV2Fingerprint.method.returnEarly(true)
        IsAppOpenAdLoadedFingerprint.method.returnEarly(true)

        // ---- Rewarded: fake loaded + instant reward ---------------------------
        // loadRewardAdV2 → synthetic 1003 LOADED (AdType.Reward), no fetch.
        val loadRewardMethod = LoadRewardAdV2Fingerprint.method
        val loadRewardExpanded = loadRewardMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(loadRewardMethod.definingClass).methods.apply {
            remove(loadRewardMethod)
            add(loadRewardExpanded)
        }
        loadRewardExpanded.addInstructions(0, """
            # v0 = new Yodo1MasAdEvent(0x3eb /*1003 LOADED*/, AdType.Reward)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x3eb
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Reward:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            # v1 = gameObject, v2 = methodName, v3 = flag(FLAG_AD_EVENT=1)
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // showRewardAdV2 → 2001 REWARD_EARNED then 1002 CLOSED (instant grant).
        val showRewardMethod = ShowRewardAdV2Fingerprint.method
        val showRewardExpanded = showRewardMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(showRewardMethod.definingClass).methods.apply {
            remove(showRewardMethod)
            add(showRewardExpanded)
        }
        showRewardExpanded.addInstructions(0, """
            # Event 1 — 2001 REWARD_EARNED (C# grants the reward)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x7d1
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Reward:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            # Event 2 — 1002 CLOSED (completes the lifecycle, un-pauses any gate)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x3ea
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Reward:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ---- Interstitial: fake loaded + instant close ------------------------
        val loadInterstitialMethod = LoadInterstitialAdV2Fingerprint.method
        val loadInterstitialExpanded = loadInterstitialMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(loadInterstitialMethod.definingClass).methods.apply {
            remove(loadInterstitialMethod)
            add(loadInterstitialExpanded)
        }
        loadInterstitialExpanded.addInstructions(0, """
            # fake 1003 LOADED (AdType.Interstitial) — never fetched
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x3eb
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Interstitial:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // showInterstitialAdV2 → 1002 CLOSED — resume now, no interstitial.
        val showInterstitialMethod = ShowInterstitialAdV2Fingerprint.method
        val showInterstitialExpanded = showInterstitialMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(showInterstitialMethod.definingClass).methods.apply {
            remove(showInterstitialMethod)
            add(showInterstitialExpanded)
        }
        showInterstitialExpanded.addInstructions(0, """
            # 1002 CLOSED (AdType.Interstitial)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x3ea
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Interstitial:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ---- App-open: fake loaded + instant close ----------------------------
        val loadAppOpenMethod = LoadAppOpenAdFingerprint.method
        val loadAppOpenExpanded = loadAppOpenMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(loadAppOpenMethod.definingClass).methods.apply {
            remove(loadAppOpenMethod)
            add(loadAppOpenExpanded)
        }
        loadAppOpenExpanded.addInstructions(0, """
            # fake 1003 LOADED (AdType.AppOpen) — splash ad never fetched
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x3eb
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->AppOpen:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // showAppOpenAd → 1002 CLOSED only (grants nothing, but C# may wait).
        val showAppOpenMethod = ShowAppOpenAdFingerprint.method
        val showAppOpenExpanded = showAppOpenMethod.cloneMutable(additionalRegisters = 4)
        mutableClassDefBy(showAppOpenMethod.definingClass).methods.apply {
            remove(showAppOpenMethod)
            add(showAppOpenExpanded)
        }
        showAppOpenExpanded.addInstructions(0, """
            # 1002 CLOSED (AdType.AppOpen)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            const/16 v1, 0x3ea
            sget-object v2, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->AppOpen:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            invoke-direct {v0, v1, v2}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}000()Ljava/lang/String;
            move-result-object v1
            invoke-static {}, Lcom/yodo1/mas/UnityYodo1Mas;->access${'$'}100()Ljava/lang/String;
            move-result-object v2
            const/4 v3, 0x1
            invoke-static {v0, v3, v1, v2}, Lcom/yodo1/mas/b;->a(Lcom/yodo1/mas/event/Yodo1MasAdEvent;ILjava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ---- Banner: pure no-ops (view never created, no request goes out) ----
        LoadBannerAdV2Fingerprint.method.returnEarly()
        ShowBannerAdV2Fingerprint.method.returnEarly()
        HideBannerAdV2Fingerprint.method.returnEarly()
        DestroyBannerAdV2Fingerprint.method.returnEarly()

        // ---- Native: pure no-ops ----------------------------------------------
        LoadNativeAdFingerprint.method.returnEarly()
        ShowNativeAdFingerprint.method.returnEarly()
        HideNativeAdFingerprint.method.returnEarly()
        DestroyNativeAdFingerprint.method.returnEarly()

        // ---- SDK-layer backstop: no full-screen ad renders from inside MAS ----
        FullScreenShowAdFingerprint.method.returnEarly()
    }
}

package app.rodeosafari.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneParameters
import app.rodeosafari.patches.shared.Constants.COMPATIBILITY_RODEO

/**
 * T1 — Rodeo Stampede Ad-Free (Ads blocked + instant rewards).
 *
 * Hooks the Unity→MAS ad bridge com.yodo1.mas.UnityYodo1Mas (classes9.dex, R8-stable —
 * Unity C# loads it reflectively by exact name, same contract as R1's UnityYodo1Payment).
 * All method signatures verified in smali before writing.
 *
 * Per-format behavior:
 *  - REWARDED: isRewardedAdLoadedV2() → true (game always thinks an ad is cached);
 *    loadRewardAdV2 emits a fake 1003 LOADED event instead of fetching; showRewardAdV2
 *    synchronously emits 2001 REWARD_EARNED then 1002 CLOSED via the same MAS event
 *    channel the SDK's real listeners use (Yodo1MasAdEvent → sendMessage → UnitySendMessage)
 *    → the game's C# onRewardAdEarned grants the configured reward with ZERO network traffic.
 *  - INTERSTITIAL: isInterstitialAdLoadedV2() → true; loadInterstitialAdV2 emits 1003;
 *    showInterstitialAdV2 emits 1002 CLOSED immediately → no pause/hang, no ad.
 *  - BANNER / APP-OPEN / NATIVE: load* + show* → pure no-op (return-void). The view is
 *    never created and the request never goes out → nothing renders, no network request.
 *
 * Event dispatch is byte-identical to the SDK's own listeners ($7$1/$10$1 etc. do exactly:
 * new Yodo1MasAdEvent(code, AdType) → getJSONObject().toString() → sendMessage(flag=1,
 * gameObject, methodName, json), where sendMessage wraps {"flag":1,"data":"<event>"} and
 * calls UnityPlayer.UnitySendMessage). We reuse the same construction so the C# parser
 * receives type/code/message exactly as with real ads.
 *
 * Codes (verified in UnityYodo1Mas$*$1 smali + Yodo1MasAdEvent):
 *  0x3e9 = 1001 OPENED, 0x3ea = 1002 CLOSED, 0x3eb = 1003 LOADED, 0x7d1 = 2001 REWARD_EARNED.
 * AdType values (Yodo1Mas$AdType.<clinit>): Reward = 1, Interstitial = 2.
 *
 * Register budget — CLONE-EXPANDED (verified): all load* / show* targets are
 * .registers 3 (v0 local + p0/p1 params). cloneParameters() adds 2 registers
 * (registers 5: v0-v2 locals + p0/p1 moved to v3/v4) and swaps the original out —
 * the injected event-dispatch uses v0..v4, exactly the intothedead ads pattern.
 * The is*LoadedV2 targets are .registers 1 (v0 only) — const/4 v0 fits.
 */
@Suppress("unused")
val rodeoSafariAdFreePatch = bytecodePatch(
    name = "Rodeo Stampede Ad-Free (Ads blocked + instant rewards)",
    description = "Blocks banner/app-open/native ads and turns rewarded + interstitial ads into instant no-ad events so the game never fetches or displays ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_RODEO)

    execute {
        // ---- Rewarded: always ready + instant reward ------------------------------
        IsRewardedAdLoadedV2Fingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // loadRewardAdV2 → fake 1003 LOADED (no network fetch).
        // Same dispatch the real onRewardAdLoaded listener uses.
        LoadRewardAdV2Fingerprint.method.cloneParameters().addInstructions(0, """
            # v0 = new Yodo1MasAdEvent(0x3eb /*1003 LOADED*/, AdType.Reward)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            sget-object v1, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Reward:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            const/16 v2, 0x3eb
            invoke-direct {v0, v2, v1}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            # v0 = event.getJSONObject().toString()
            invoke-virtual {v0}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;->getJSONObject()Lorg/json/JSONObject;
            move-result-object v0
            invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v0
            # v1 = gameObject, v2 = methodName, v3 = flag(FLAG_AD_EVENT=1)
            sget-object v1, Lcom/yodo1/mas/UnityYodo1Mas;->gameObject:Ljava/lang/String;
            sget-object v2, Lcom/yodo1/mas/UnityYodo1Mas;->methodName:Ljava/lang/String;
            const/4 v3, 0x1
            invoke-static {v3, v1, v2, v0}, Lcom/yodo1/mas/UnityYodo1Mas;->sendMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // showRewardAdV2 → 2001 REWARD_EARNED then 1002 CLOSED (instant grant).
        ShowRewardAdV2Fingerprint.method.cloneParameters().addInstructions(0, """
            # Event 1 — 2001 REWARD_EARNED (grants the reward in C#)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            sget-object v1, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Reward:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            const/16 v2, 0x7d1
            invoke-direct {v0, v2, v1}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-virtual {v0}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;->getJSONObject()Lorg/json/JSONObject;
            move-result-object v0
            invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v0
            sget-object v1, Lcom/yodo1/mas/UnityYodo1Mas;->gameObject:Ljava/lang/String;
            sget-object v2, Lcom/yodo1/mas/UnityYodo1Mas;->methodName:Ljava/lang/String;
            const/4 v3, 0x1
            invoke-static {v3, v1, v2, v0}, Lcom/yodo1/mas/UnityYodo1Mas;->sendMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            # Event 2 — 1002 CLOSED (completes the lifecycle, un-pauses any gate)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            sget-object v1, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Reward:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            const/16 v2, 0x3ea
            invoke-direct {v0, v2, v1}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-virtual {v0}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;->getJSONObject()Lorg/json/JSONObject;
            move-result-object v0
            invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v0
            sget-object v1, Lcom/yodo1/mas/UnityYodo1Mas;->gameObject:Ljava/lang/String;
            sget-object v2, Lcom/yodo1/mas/UnityYodo1Mas;->methodName:Ljava/lang/String;
            const/4 v3, 0x1
            invoke-static {v3, v1, v2, v0}, Lcom/yodo1/mas/UnityYodo1Mas;->sendMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ---- Interstitial: always ready + instant close ---------------------------
        IsInterstitialAdLoadedV2Fingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        LoadInterstitialAdV2Fingerprint.method.cloneParameters().addInstructions(0, """
            # fake 1003 LOADED (no interstitial ever fetched)
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            sget-object v1, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Interstitial:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            const/16 v2, 0x3eb
            invoke-direct {v0, v2, v1}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-virtual {v0}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;->getJSONObject()Lorg/json/JSONObject;
            move-result-object v0
            invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v0
            sget-object v1, Lcom/yodo1/mas/UnityYodo1Mas;->gameObject:Ljava/lang/String;
            sget-object v2, Lcom/yodo1/mas/UnityYodo1Mas;->methodName:Ljava/lang/String;
            const/4 v3, 0x1
            invoke-static {v3, v1, v2, v0}, Lcom/yodo1/mas/UnityYodo1Mas;->sendMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        ShowInterstitialAdV2Fingerprint.method.cloneParameters().addInstructions(0, """
            # 1002 CLOSED — game resumes immediately, no interstitial renders
            new-instance v0, Lcom/yodo1/mas/event/Yodo1MasAdEvent;
            sget-object v1, Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;->Interstitial:Lcom/yodo1/mas/Yodo1Mas${'$'}AdType;
            const/16 v2, 0x3ea
            invoke-direct {v0, v2, v1}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;-><init>(ILcom/yodo1/mas/Yodo1Mas${'$'}AdType;)V
            invoke-virtual {v0}, Lcom/yodo1/mas/event/Yodo1MasAdEvent;->getJSONObject()Lorg/json/JSONObject;
            move-result-object v0
            invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
            move-result-object v0
            sget-object v1, Lcom/yodo1/mas/UnityYodo1Mas;->gameObject:Ljava/lang/String;
            sget-object v2, Lcom/yodo1/mas/UnityYodo1Mas;->methodName:Ljava/lang/String;
            const/4 v3, 0x1
            invoke-static {v3, v1, v2, v0}, Lcom/yodo1/mas/UnityYodo1Mas;->sendMessage(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ---- Banner / App-Open / Native: pure no-ops ------------------------------
        LoadBannerAdV2Fingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        ShowBannerAdV2Fingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        LoadAppOpenAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        ShowAppOpenAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        LoadNativeAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        ShowNativeAdFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())
    }
}

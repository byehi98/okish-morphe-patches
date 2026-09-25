package app.subwaysurfers.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import app.subwaysurfers.patches.shared.Constants.COMPATIBILITY_SUBWAY_SURFERS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

// Inner-class descriptor (smali uses '$'). Single-quoted const keeps Kotlin
// from treating $ as template start; interpolated verbatim into the raw-string
// smali below (same pattern as billing/NoPlayUiPatch's PRODUCT_DETAILS_PARAMS).
private const val SHOW_COMPLETION_STATE = "Lcom/unity3d/ads/UnityAds\$UnityAdsShowCompletionState;"

/**
 * Subway Surfers — Ad Removal & Free Rewards (single combined patch).
 *
 * Kills every ad surface AND forges the rewarded-video callbacks the C# game
 * waits on, so coins/revives/daily-reward offers grant instantly with no video.
 *
 * ── Verified architecture (smali + IL2CPP dump) ─────────────────────────────
 *   C# SYBO AdManager / AdPlacementsManager (libil2cpp)
 *     ├─ Unity.Services.LevelPlay Android{Rewarded,Interstitial,Banner}Ad
 *     │    └─ JNI → com.ironsource.unity.androidbridge.{RewardedAd,
 *     │         InterstitialAd,BannerAd}  ← OUR CHOKE POINTS (classes5)
 *     │         └─ LevelPlay*Ad waterfall (never reached / never shown)
 *     └─ Unity Ads direct: NOT USED — dump.cs contains zero "com.unity3d.ads."
 *        strings and the AndroidJavaProxy ad proxies are only
 *        com.ironsource.unity.androidbridge.* (UnityBannerAdListener,
 *        UnityInterstitialAdListener, UnityRewardedAdListener, …).
 *
 * LevelPlayBridge carries ONLY init/config methods (no show/load), so these
 * bridges are the complete display surface.
 *
 * ── 1. Rewarded (Target 1 — PRIMARY) ────────────────────────────────────────
 * classes5/.../RewardedAd.smali:
 * - isAdReady()Z (:204, .registers 2) → returnEarly(true). C#
 *   AndroidRewardedAd.IsAdReady (0x436F378) runs <IsAdReady>b__52_0
 *   (0x436FBE8) via ThreadUtil.Send, which JNI-calls THIS method and stores
 *   the result into m_IsReady before returning it — so the forced true is
 *   exactly what the game reads (not a stale cache).
 * - isPlacementCapped(String)Z (:83, .registers 1 static) → returnEarly(false)
 *   so capping can never deny an offer.
 * - setupRewardedListener (:139, .registers 4; p0=v2, p1=v3) ← iput at index 0
 *   stores the C# proxy in a NEW instance field mUnityRewardedAdListener.
 *   The proxy is otherwise unreachable: the ctor (:15) passes it straight into
 *   RewardedAd$1 + LevelPlayRewardedAd.setListener and keeps no reference.
 *   The 1-instruction block needs no register expansion and carries no labels.
 * - loadAd()V (:223, .registers 2) → cloned with +2 registers (.registers 4,
 *   p0=v3, locals v0-v2) and prefixed with: listener null → fall through to
 *   the REAL load; else fire synthetic onAdLoaded("{}") and return. No ad is
 *   ever fetched. "{}" is safe: loadAd has no placement parameter, C#
 *   onAdLoaded → LevelPlayAdInfo("{}") → SYBO AdsDataExtensions.ToSyboAdInfo
 *   (0x3C01990) is pure field copies with no key access, and the identical
 *   recipe is device-proved in intothedead2's ads patch on this same bridge.
 * - showAd(String)V (:279, .registers 4) → cloned with +4 registers
 *   (.registers 8, p0=v6, p1=v7, locals v0-v5) and prefixed with the forged
 *   lifecycle — listener null → fall through to the real SDK call:
 *
 *     onAdDisplayed(adInfo) → onAdRewarded(adInfo, name, amount) → onAdClosed
 *
 *   adInfo = {"placementName":"<p1>","adFormat":"REWARDED","adNetwork":"morphe"}
 *   built with StringBuilder. placementName is included because C#
 *   IronSourceRewardedAd.OnAdRewarded (0x3C02FE0) null-THROWS on info/reward
 *   and then reads info.PlacementName (LevelPlayAdInfo+0x38) to build the
 *   SYBO Placement passed through OnRewardedAdSuccess — a real show would
 *   carry it, so we do too. The JSON is tolerant on the C# side:
 *   LevelPlayAdInfo.ctor(string json) (0x435FD38) early-outs on null/empty,
 *   parses via LevelPlayJson.Parser, and every key lookup is optional
 *   (tbz → skip), so any key we omit is simply null.
 *
 *   Reward name/amount are resolved LIVE — invoke-virtual getReward(placement)
 *   (RewardedAd.smali:185 → LevelPlayRewardedAd.getReward:382), falling back
 *   to `new LevelPlayReward(placement, 1)` when the SDK has no config
 *   (Java ctor (String,I) verified: LevelPlayReward.smali:16), mirroring the
 *   intothedead2 device-proved recipe. The values are provably inert for the
 *   grant: the two IRewardedAdListener implementations in dump.cs
 *   (AdsHandler.OnRewardedAdClosed 0x21AEC7C, AdsButtonWidget.OnRewardedAdClosed
 *   0x2202504) IGNORE the Placement argument, the grant goes through
 *   AdPlacementsManager.NotifyRewardedAdShown(placementId) (0x23276f8) keyed
 *   by placement id alone, and the game's own backfill ads construct
 *   Placement(..., rewardAmount=0) (SubwayBackfillRewardedAd.OnSuccess
 *   0x22EC9F8 / BackfillRewardedAd.OnSuccess 0x3CBB824) — amount is unused
 *   game-wide.
 *
 *   Callback ordering is safe: C# ShowAd (0x436F29C) posts via
 *   ThreadUtil.Post and every Java→C# proxy callback is marshalled through
 *   ThreadUtil.Post too, so the three invokes arrive FIFO in order, long
 *   after the JNI call returns (no re-entrancy).
 *
 * ── 2. Interstitials (Target 2) ─────────────────────────────────────────────
 * classes5/.../InterstitialAd.smali — same four moves as rewarded:
 * - isAdReady()Z (:185, .registers 2) → true; isPlacementCapped (:83) → false.
 * - setupInterstitialListener (:139, .registers 4) → capture proxy into new
 *   field mUnityInterstitialAdListener (InterstitalAd ctor passes it to
 *   InterstitialAd$1 + setListener only).
 * - loadAd()V (:204, .registers 2) → +2 clone, synthetic onAdLoaded("{}").
 * - showAd(String)V (:260, .registers 4) → +4 clone, forges
 *   onAdDisplayed(adInfo) → onAdClosed(adInfo) with
 *   {"placementName":"<p1>","adFormat":"INTERSTITIAL","adNetwork":"morphe"}.
 *   No reward legs (interface has no onAdRewarded). Firing the close matters:
 *   SYBO's interstitial state machine is callback-driven, and a show that
 *   never reports close can strand a between-run flow — the note's soft-lock
 *   caveat applies here too, just with lower stakes than rewarded.
 *
 * ── 3. Banners ──────────────────────────────────────────────────────────────
 * classes5/.../BannerAd.smali — load()V (:1217, .registers 2) and showAd()V
 * (:1305, .registers 3) → returnEarly() (return-void). load() is the real
 * fetch (mBannerAdView.loadAd), showAd() posts BannerAd$2 which flips the
 * view VISIBLE — suppressing BOTH means the (transparent-background, empty)
 * view never gets content and is never revealed, even with the ctor's
 * displayOnLoad flag (BannerAd ctor → setup(..., displayOnLoad, ...)).
 *
 * No banner callbacks are forged on purpose: the BannerAd bridge keeps no
 * listener field (setup wraps it in BannerAd$1 for the view only), and the
 * wait is bounded — AdManager.LoadBanner(..., int timeoutInSeconds)
 * (dump.cs 856840) plus the dedicated NoBannerLoadedException (857501) mean a
 * load that never answers ends as a handled banner failure. Banners gate
 * nothing (no reward path), so graceful absence is the correct end state.
 *
 * ── 4. Unity Ads (Target 4 — defense in depth) ──────────────────────────────
 * classes6/com/unity3d/ads/UnityAds.smali — all four show overloads at :1387
 * (.registers 5, p0=v3/p1=v4), :1440 (p0=v2/p1=v3/p2=v4), :1495 (p0=v2/p1=v3/
 * p2=v4) and :1548 (p0=v1..p3=v4, local v0) are body-replaced with a
 * straight-line call to the fresh helper UnityAds.morpheForgeShowComplete +
 * return-void (no labels — these methods carry exception tables and Morphe's
 * writer drops labeled blocks in methods with exception tables; the dead
 * originals after return-void are the proven NoPlayUiPatch body-replace
 * pattern). The 2-arg/3-arg overloads pass const 0x0 as the null listener,
 * exactly like the originals.
 *
 * Helper (fresh static method, .registers 3: p0=v1 placement, p1=v2 listener,
 * local v0 — labels safe, no exception table): null-check listener, then
 * sget-object the completion state and fire
 *   IUnityAdsShowListener.onUnityAdsShowComplete(placement, COMPLETED)
 * The enum values are SKIPPED/COMPLETED
 * (UnityAds$UnityAdsShowCompletionState.smali) — NOT the newer
 * "SHOW_COMPLETED" the note sketched; using the wrong constant would throw
 * NoSuchFieldError at runtime. Never a bare return: an awaiter on the
 * completion callback would hang (same soft-lock class as Target 1).
 *
 * Reachability note: these overloads sit behind mediation adapters
 * (com.google.ads.mediation.unity.c → UnityAds.show; LevelPlay's UnityAds
 * adapter → com.unity3d.ads.RewardedAd.show) which are themselves unreachable
 * because the LevelPlay bridge above them is fully forged — so this is pure
 * belt-and-braces per the note's Target 4.
 *
 * ── Deviation from notes/ad-removal.md Target 3 (documented) ────────────────
 * LevelPlayBridge.initialize (:111) is deliberately NOT patched. The C# init
 * chain is event-driven — IronSourceAdManagerProvider.OnProviderInitComplete/
 * OnProviderInitFailed (k_InitMaxAttempts = 62) — and initialize() stores the
 * init listener BEFORE calling LevelPlay.init (:132 iput → :145), so
 * returnEarly would suppress BOTH the success and the failure callback and we
 * could not prove that no async state machine awaits init completion. The
 * benefit is only skipping a config fetch: load() is suppressed on all three
 * bridges, every show path is forged or suppressed, so with init running NO
 * ad creative is ever fetched or displayed anyway. Leaving it alone removes
 * the only boot-hang risk the note itself flagged ("init-skip alone breaks
 * rewards").
 *
 * ── Register budgets (verified against smali) ───────────────────────────────
 * - RewardedAd.isAdReady            .registers 2 (p0=v1, local v0) — returnEarly uses v0.
 * - RewardedAd.isPlacementCapped    .registers 1 (static; v0 IS the p0 slot;
 *   written then returned, nothing reads it after).
 * - setupRewardedListener /
 *   setupInterstitialListener       .registers 4 (p0=v2, p1=v3) — 1-instruction
 *   iput, no expansion needed.
 * - loadAd clones (rewarded + interstitial): +2 → .registers 4
 *   (p0=v3, locals v0-v2; block writes v0/v1 only; fall-through path untouched).
 * - showAd clones (rewarded): +4 → .registers 8 (p0=v6, p1=v7, locals v0-v5;
 *   block writes v0-v3; p0 is only read after the listener branch on the
 *   forge path, p1 is only overwritten by the null-guard after :cond_real).
 * - showAd clones (interstitial): +4 → .registers 8 (block writes v0-v2).
 * - BannerAd.load / BannerAd.showAd: .registers 2 / .registers 3 — a single
 *   return-void, no registers touched.
 * - UnityAds.show ×4: .registers 5 each — 2-arg and options-arg overloads
 *   borrow LOCAL v0 for the null listener (never a param slot); the listener
 *   overloads pass p2/p3 directly; no writes at all in the 4-arg overload.
 * - morpheForgeShowComplete (injected): .registers 3 (p0=v1, p1=v2, local v0).
 * - Labels exist ONLY in fresh methods (morpheForgeShowComplete) and in the
 *   cloned loadAd/showAd bodies — those originals carry no exception table
 *   (no .catch anywhere in them, verified), so labeled blocks are safe there.
 *
 * RUNTIME-VERIFY (device test):
 * - Rewarded offer → instant grant, no video, no soft-lock (rewarded path is
 *   callback-ordered: displayed → rewarded → closed).
 * - Between-run interstitial never appears; run flow continues.
 * - Banner slots stay empty (banner load times out gracefully).
 * - Restart: init still runs (config fetch only), same behavior.
 * - Unity Ads overloads: dead code in this build (no C# caller) — kept as
 *   insurance only.
 */
@Suppress("unused")
val subwaysurfersAdRemovalPatch = bytecodePatch(
    name = "Ad Removal & Free Rewards",
    description = "Removes all ads. \"Watch a video\" rewards (coins, revives, bonuses) are granted instantly, without watching anything.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SUBWAY_SURFERS)

    execute {
        // ═══ 1. Injected helper (fresh method → labeled body survives) ══════

        // UnityAds.morpheForgeShowComplete(String, IUnityAdsShowListener)V —
        // fire onUnityAdsShowComplete(placement, COMPLETED) on a non-null
        // listener; null listener (the 2-arg/3-arg overloads) is a no-op.
        val unityAdsClass = UnityAdsShowNoListenerFingerprint.classDef
        val morpheForgeShowComplete = ImmutableMethod(
            "Lcom/unity3d/ads/UnityAds;",
            "morpheForgeShowComplete",
            listOf(
                ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ImmutableMethodParameter("Lcom/unity3d/ads/IUnityAdsShowListener;", null, null)
            ),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                if-eqz p1, :done
                nop
                sget-object v0, $SHOW_COMPLETION_STATE->COMPLETED:$SHOW_COMPLETION_STATE
                invoke-interface {p1, p0, v0}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowComplete(Ljava/lang/String;$SHOW_COMPLETION_STATE)V

                :done
                nop
                return-void
            """.trimIndent())
        }
        unityAdsClass.methods.add(morpheForgeShowComplete)

        // ═══ 2. Rewarded — availability + listener capture ══════════════════
        RewardedAdIsAdReadyFingerprint.method.returnEarly(true)
        RewardedAdIsPlacementCappedFingerprint.method.returnEarly(false)

        RewardedAdShowAdFingerprint.classDef.fields.add(
            ImmutableField(
                "Lcom/ironsource/unity/androidbridge/RewardedAd;",
                "mUnityRewardedAdListener",
                "Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;",
                AccessFlags.PRIVATE.value,
                null, // initialValue
                null, // annotations
                null  // hiddenApiRestrictions
            ).toMutable()
        )
        RewardedAdSetupListenerFingerprint.method.addInstructions(0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
        """.trimIndent())

        // ═══ 3. Rewarded — synthetic load, forged show lifecycle ═════════════
        // loadAd: +2 registers; null listener → real load, else onAdLoaded.
        val rewardedLoadAd = RewardedAdLoadAdFingerprint.method.cloneMutable(additionalRegisters = 2)
        RewardedAdLoadAdFingerprint.classDef.methods.apply {
            remove(RewardedAdLoadAdFingerprint.method)
            add(rewardedLoadAd)
        }
        rewardedLoadAd.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            const-string v1, "{}"
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdLoaded(Ljava/lang/String;)V
            return-void

            :cond_real
            nop
        """.trimIndent())

        // showAd: +4 registers; null listener → real ad, else forged
        // displayed → rewarded (live getReward / fallback) → closed.
        val rewardedShowAd = RewardedAdShowAdFingerprint.method.cloneMutable(additionalRegisters = 4)
        RewardedAdShowAdFingerprint.classDef.methods.apply {
            remove(RewardedAdShowAdFingerprint.method)
            add(rewardedShowAd)
        }
        rewardedShowAd.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real
            if-nez p1, :pl_ok
            const-string p1, ""
            :pl_ok
            nop

            # adInfo JSON with the placement the game's OnAdRewarded reads back
            new-instance v1, Ljava/lang/StringBuilder;
            invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
            const-string v2, "{\"placementName\":\""
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v2, "\",\"adFormat\":\"REWARDED\",\"adNetwork\":\"morphe\"}"
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v1

            # reward resolved live from the SDK config, null → fallback pair
            invoke-virtual {p0, p1}, Lcom/ironsource/unity/androidbridge/RewardedAd;->getReward(Ljava/lang/String;)Lcom/unity3d/mediation/rewarded/LevelPlayReward;
            move-result-object v2
            if-nez v2, :has_reward
            nop
            new-instance v2, Lcom/unity3d/mediation/rewarded/LevelPlayReward;
            const/4 v3, 0x1
            invoke-direct {v2, p1, v3}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;-><init>(Ljava/lang/String;I)V
            :has_reward
            nop
            invoke-virtual {v2}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;->getName()Ljava/lang/String;
            move-result-object v3
            invoke-virtual {v2}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;->getAmount()I
            move-result v2

            # lifecycle — order matters to C# (displayed → rewarded → closed)
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdDisplayed(Ljava/lang/String;)V
            invoke-interface {v0, v1, v3, v2}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;I)V
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void

            :cond_real
            nop
        """.trimIndent())

        // ═══ 4. Interstitials — same four moves, no reward legs ══════════════
        InterstitialAdIsAdReadyFingerprint.method.returnEarly(true)
        InterstitialAdIsPlacementCappedFingerprint.method.returnEarly(false)

        InterstitialAdShowAdFingerprint.classDef.fields.add(
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
        InterstitialAdSetupListenerFingerprint.method.addInstructions(0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/InterstitialAd;->mUnityInterstitialAdListener:Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;
        """.trimIndent())

        val interstitialLoadAd = InterstitialAdLoadAdFingerprint.method.cloneMutable(additionalRegisters = 2)
        InterstitialAdLoadAdFingerprint.classDef.methods.apply {
            remove(InterstitialAdLoadAdFingerprint.method)
            add(interstitialLoadAd)
        }
        interstitialLoadAd.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/InterstitialAd;->mUnityInterstitialAdListener:Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;
            if-eqz v0, :cond_real
            const-string v1, "{}"
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;->onAdLoaded(Ljava/lang/String;)V
            return-void

            :cond_real
            nop
        """.trimIndent())

        val interstitialShowAd = InterstitialAdShowAdFingerprint.method.cloneMutable(additionalRegisters = 4)
        InterstitialAdShowAdFingerprint.classDef.methods.apply {
            remove(InterstitialAdShowAdFingerprint.method)
            add(interstitialShowAd)
        }
        interstitialShowAd.addInstructionsWithLabels(0, """
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/InterstitialAd;->mUnityInterstitialAdListener:Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;
            if-eqz v0, :cond_real
            if-nez p1, :pl_ok
            const-string p1, ""
            :pl_ok
            nop

            # adInfo JSON (placementName read by the C# ad-info parser)
            new-instance v1, Ljava/lang/StringBuilder;
            invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
            const-string v2, "{\"placementName\":\""
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v2, "\",\"adFormat\":\"INTERSTITIAL\",\"adNetwork\":\"morphe\"}"
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v1

            # displayed → closed completes the between-run flow
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;->onAdDisplayed(Ljava/lang/String;)V
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;->onAdClosed(Ljava/lang/String;)V
            return-void

            :cond_real
            nop
        """.trimIndent())

        // ═══ 5. Banners — no fetch, no reveal ════════════════════════════════
        BannerAdLoadFingerprint.method.returnEarly()
        BannerAdShowAdFingerprint.method.returnEarly()

        // ═══ 6. Unity Ads show overloads — forge completion, never hang ══════
        // .registers 5 each; straight-line (no labels → exception-table safe);
        // listener overloads pass their listener through, the others pass null.
        UnityAdsShowNoListenerFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            invoke-static {p1, v0}, Lcom/unity3d/ads/UnityAds;->morpheForgeShowComplete(Ljava/lang/String;Lcom/unity3d/ads/IUnityAdsShowListener;)V
            return-void
        """.trimIndent())
        UnityAdsShowListenerFingerprint.method.addInstructions(0, """
            invoke-static {p1, p2}, Lcom/unity3d/ads/UnityAds;->morpheForgeShowComplete(Ljava/lang/String;Lcom/unity3d/ads/IUnityAdsShowListener;)V
            return-void
        """.trimIndent())
        UnityAdsShowOptionsFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            invoke-static {p1, v0}, Lcom/unity3d/ads/UnityAds;->morpheForgeShowComplete(Ljava/lang/String;Lcom/unity3d/ads/IUnityAdsShowListener;)V
            return-void
        """.trimIndent())
        UnityAdsShowOptionsListenerFingerprint.method.addInstructions(0, """
            invoke-static {p1, p3}, Lcom/unity3d/ads/UnityAds;->morpheForgeShowComplete(Ljava/lang/String;Lcom/unity3d/ads/IUnityAdsShowListener;)V
            return-void
        """.trimIndent())
    }
}

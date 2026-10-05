package app.shootyskies.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Shooty Skies — Unity↔MAS ad bridge: com.yodo1.mas.UnityYodo1Mas (classes19)
//
// R8-stable: Yodo1 keeps the bridge class/method names unobfuscated because
// Unity C# loads them reflectively by exact name (all signatures appear
// verbatim in global-metadata.dat). Every method body was read in smali before
// writing — line numbers below are from this build (3.441.100101).
//
// Common shape of every load*/show*V2 target (all .registers 3, static):
//   new-instance v0, UnityYodo1Mas$<n>;
//   invoke-direct {v0, p1, p0}, $<n>.<init>(String, Activity);
//   invoke-virtual {p0, v0}, Activity.runOnUiThread(Runnable);
//   return-void
// → filter order is always "<inner>.<init>" then "runOnUiThread" (verified).
// hide*/destroy* construct their inner class with (String) only — the filter
// still matches: definingClass + "<init>" is enough.
// ============================================================================

/**
 * UnityYodo1Mas.isRewardedAdLoadedV2()Z — C# rewarded-ad readiness poll.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1192, `.registers 1`, static, no params):
 *   Yodo1MasRewardAd.getInstance() → Yodo1MasFullScreenAd.isLoaded()Z
 * (the invoke-virtual is declared on the shared base class, hence that
 * definingClass in the second filter).
 *
 * Forcing true keeps the "watch ad for reward" call-to-action enabled; the
 * show path then grants instantly without a video.
 */
object IsRewardedAdLoadedV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "isRewardedAdLoadedV2",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/reward/Yodo1MasRewardAd;",
            name = "getInstance",
        ),
        methodCall(
            definingClass = "Lcom/yodo1/mas/ad/Yodo1MasFullScreenAd;",
            name = "isLoaded",
        ),
    )
)

/**
 * UnityYodo1Mas.loadRewardAdV2(Activity, String)V — C# rewarded-ad load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1259, `.registers 3`). Posts
 * UnityYodo1Mas$7 to the UI thread (registers the real listener and fetches a
 * network ad). Replaced with a synthetic 1003 LOADED event dispatched through
 * the SDK's own channel — zero network traffic.
 *
 * Filter order matches smali exactly: $7.<init> → Activity.runOnUiThread.
 */
object LoadRewardAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "loadRewardAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$7",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showRewardAdV2(Activity, String)V — C# rewarded-ad display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1861, `.registers 3`). Posts
 * UnityYodo1Mas$8 → Yodo1MasRewardAd.showAd — a real video plays and the
 * reward only arrives afterwards. Replaced with a synchronous
 * 2001 REWARD_EARNED → 1002 CLOSED pair so C# grants immediately, no ad shown.
 *
 * Filter order matches smali exactly: $8.<init> → Activity.runOnUiThread.
 */
object ShowRewardAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "showRewardAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$8",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.isInterstitialAdLoadedV2()Z — C# interstitial readiness poll.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1177, `.registers 1`):
 *   Yodo1MasInterstitialAd.getInstance() → Yodo1MasFullScreenAd.isLoaded()Z
 */
object IsInterstitialAdLoadedV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "isInterstitialAdLoadedV2",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/interstitial/Yodo1MasInterstitialAd;",
            name = "getInstance",
        ),
        methodCall(
            definingClass = "Lcom/yodo1/mas/ad/Yodo1MasFullScreenAd;",
            name = "isLoaded",
        ),
    )
)

/**
 * UnityYodo1Mas.loadInterstitialAdV2(Activity, String)V — C# interstitial load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1233, `.registers 3`). Posts
 * UnityYodo1Mas$10 → registers listeners + network fetch. Replaced with a
 * synthetic 1003 LOADED event — no interstitial is ever fetched.
 */
object LoadInterstitialAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "loadInterstitialAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$10",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showInterstitialAdV2(Activity, String)V — C# interstitial display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1822, `.registers 3`). Posts
 * UnityYodo1Mas$11 → full screen ad renders. Replaced with a synthetic
 * 1002 CLOSED event: the mission flow resumes immediately, no interstitial.
 */
object ShowInterstitialAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "showInterstitialAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$11",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.isAppOpenAdLoaded()Z — splash/resume ad readiness poll.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1117, `.registers 1` — note: NO V2
 * suffix in this SDK version):
 *   Yodo1MasAppOpenAd.getInstance() → Yodo1MasFullScreenAd.isLoaded()Z
 */
object IsAppOpenAdLoadedFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "isAppOpenAdLoaded",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/appopenad/Yodo1MasAppOpenAd;",
            name = "getInstance",
        ),
        methodCall(
            definingClass = "Lcom/yodo1/mas/ad/Yodo1MasFullScreenAd;",
            name = "isLoaded",
        ),
    )
)

/**
 * UnityYodo1Mas.loadAppOpenAd(Activity, String)V — C# app-open load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1207, `.registers 3`). Posts
 * UnityYodo1Mas$13 → app-open fetch. Replaced with synthetic 1003 LOADED
 * (AdType.AppOpen) so nothing is ever requested.
 */
object LoadAppOpenAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "loadAppOpenAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$13",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showAppOpenAd(Activity, String)V — C# app-open display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1783, `.registers 3`). Posts
 * UnityYodo1Mas$14 → launch/resume full-screen ad. Replaced with a synthetic
 * 1002 CLOSED event only (app-open grants nothing, but C# may wait for the
 * close — a plain no-op risks a stuck splash).
 */
object ShowAppOpenAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "showAppOpenAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$14",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

// ── Banner — all .registers 3, all `new-instance → runOnUiThread → return-void` ──

/** UnityYodo1Mas.loadBannerAdV2(Activity,String)V — confirmed smali:1220, posts $20. */
object LoadBannerAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "loadBannerAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$20",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/** UnityYodo1Mas.showBannerAdV2(Activity,String)V — confirmed smali:1796, posts $21. */
object ShowBannerAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "showBannerAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$21",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/** UnityYodo1Mas.hideBannerAdV2(Activity,String)V — confirmed smali:1059, posts $22(String). */
object HideBannerAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "hideBannerAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$22",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/** UnityYodo1Mas.destroyBannerAdV2(Activity,String)V — confirmed smali:343, posts $23(String). */
object DestroyBannerAdV2Fingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "destroyBannerAdV2",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$23",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

// ── Native — same shape as banner ──

/** UnityYodo1Mas.loadNativeAd(Activity,String)V — confirmed smali:1246, posts $16. */
object LoadNativeAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "loadNativeAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$16",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/** UnityYodo1Mas.showNativeAd(Activity,String)V — confirmed smali:1835, posts $17. */
object ShowNativeAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "showNativeAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$17",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/** UnityYodo1Mas.hideNativeAd(Activity,String)V — confirmed smali:1072, posts $18(String). */
object HideNativeAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "hideNativeAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$18",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

/** UnityYodo1Mas.destroyNativeAd(Activity,String)V — confirmed smali:369, posts $19(String). */
object DestroyNativeAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/UnityYodo1Mas;",
    name = "destroyNativeAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/UnityYodo1Mas\$19",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        ),
    )
)

// ── SDK-layer backstop (classes20) ──

/**
 * Yodo1MasFullScreenAd.showAd(Activity, String, String)V — the shared
 * full-screen show entry for reward + interstitial + app-open.
 *
 * Confirmed smali (classes20 Yodo1MasFullScreenAd.smali:3366, `.registers 7`):
 *   hasCachedAd()Z → onPreShowCheck()String → trackAdShowCalled / render.
 *
 * Backstop only: every C#-reachable show*V2 is already replaced above, so
 * this cuts any remaining SDK-internal path (loadAndShowAd etc.). Paired
 * with the showRewardAdV2 event synthesis — reward grants never flow through
 * this method, so killing it drops no reward.
 */
object FullScreenShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/mas/ad/Yodo1MasFullScreenAd;",
    name = "showAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/yodo1/mas/ad/Yodo1MasFullScreenAd;",
            name = "hasCachedAd",
        ),
        methodCall(
            definingClass = "Lcom/yodo1/mas/ad/Yodo1MasFullScreenAd;",
            name = "onPreShowCheck",
        ),
    )
)

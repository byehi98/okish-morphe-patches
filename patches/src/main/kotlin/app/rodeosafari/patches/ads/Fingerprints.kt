package app.rodeosafari.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// T1 — Unity↔MAS ad bridge: com.yodo1.mas.UnityYodo1Mas (classes9.dex)
//
// R8-stable: Yodo1 keeps public bridge class/method names unobfuscated because
// Unity C# loads them reflectively by exact name (same contract as R1's
// UnityYodo1Payment). All method bodies verified in smali before writing.
// ============================================================================

/**
 * UnityYodo1Mas.isRewardedAdLoadedV2()Z — C# rewarded-ad readiness poll.
 *
 * Confirmed smali (classes9/com/yodo1/mas/UnityYodo1Mas.smali:1192), body:
 *   invoke-static {}, Lcom/yodo1/mas/reward/Yodo1MasRewardAd;->getInstance()...
 *   invoke-virtual {v0}, Lcom/yodo1/mas/reward/Yodo1MasRewardAd;->isLoaded()Z
 *
 * Forcing true = the game always believes a rewarded ad is cached → the
 * "watch ad for reward" flow stays enabled, then showRewardAdV2 grants instantly.
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
        )
    )
)

/**
 * UnityYodo1Mas.loadRewardAdV2(Activity, String)V — C# rewarded-ad load request.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1259). Normally posts UnityYodo1Mas$7 to
 * the UI thread which registers a real listener and calls
 * Yodo1MasRewardAd.loadAd(Activity) — a real network ad fetch. Replaced with a
 * synthetic 1003 LOADED event so the game believes the ad is ready WITHOUT any
 * ad being fetched (zero network traffic).
 *
 * Filter order matches smali exactly: $7 construction → runOnUiThread.
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showRewardAdV2(Activity, String)V — C# rewarded-ad display entry.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1861). Normally posts UnityYodo1Mas$8 to
 * the UI thread which calls Yodo1MasRewardAd.showAd(Activity, placement, customData)
 * — a full ad plays and the reward only arrives after the ad completes. Replaced
 * with a synchronous 2001 REWARD_EARNED → 1002 CLOSED event pair so the game's C#
 * onRewardAdEarned grants the configured reward immediately with no ad shown.
 *
 * Filter order matches smali exactly: $8 construction → runOnUiThread.
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.isInterstitialAdLoadedV2()Z — C# interstitial readiness poll.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1177). Forcing true keeps the game's
 * interstitial call-to-action enabled; showInterstitialAdV2 then closes instantly.
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
        )
    )
)

/**
 * UnityYodo1Mas.loadInterstitialAdV2(Activity, String)V — C# interstitial load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1233). Normally posts UnityYodo1Mas$10,
 * which registers listeners and calls Yodo1MasInterstitialAd.loadAd(Activity).
 * Replaced with a synthetic 1003 LOADED event — no interstitial is ever fetched.
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showInterstitialAdV2(Activity, String)V — C# interstitial display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1822). Normally posts UnityYodo1Mas$11
 * which calls Yodo1MasInterstitialAd.showAd(Activity, placement). Replaced with
 * a synthetic 1002 CLOSED event so the game immediately resumes — no interstitial
 * ever renders (no pause/hang, no ad).
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.loadBannerAdV2(Activity, String)V — C# banner load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1220). Posts UnityYodo1Mas$20 →
 * Yodo1MasBannerAdView load. No-op = banner view is never created → nothing renders.
 */
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showBannerAdV2(Activity, String)V — C# banner display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1796). Posts UnityYodo1Mas$21. No-op =
 * an empty banner never occupies layout.
 */
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.loadAppOpenAd(Activity, String)V — C# app-open load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1207). Posts UnityYodo1Mas$13 →
 * Yodo1MasAppOpenAd load. No-op = no app-open ad is ever fetched.
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showAppOpenAd(Activity, String)V — C# app-open display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1783). Posts UnityYodo1Mas$14. No-op =
 * the app-open ad never displays (kills launch/resume full-screen ads).
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.loadNativeAd(Activity, String)V — C# native-ads load.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1246). Posts UnityYodo1Mas$16 →
 * Yodo1MasNativeAdView load. No-op = native ad is never fetched.
 */
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

/**
 * UnityYodo1Mas.showNativeAd(Activity, String)V — C# native-ads display.
 *
 * Confirmed smali (UnityYodo1Mas.smali:1835). Posts UnityYodo1Mas$17. No-op =
 * native ad tile never renders.
 */
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
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

package app.subwaysurfers.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ===========================================================================
// Subway Surfers 3.69.1 — ad-removal fingerprints.
//
// All targets live in the LevelPlay ironSource bridge
// (com.ironsource.unity.androidbridge, classes5) plus the Unity Ads façade
// (com.unity3d.ads.UnityAds, classes6). Every filter uses stable SDK/Java
// names only — no obfuscated identifiers anywhere. Access flags mirror the
// smali `.method` lines EXACTLY (fingerprint-debugging: PUBLIC vs PUBLIC
// FINAL is an exact match).
//
// Line numbers verified against analysis/com.kiloo.subwaysurf/smali.
// ===========================================================================

// ---------------------------------------------------------------------------
// Rewarded ads (Target 1 — PRIMARY reward choke point)
// ---------------------------------------------------------------------------

// RewardedAd.isAdReady()Z — public. classes5/.../RewardedAd.smali:204
// (.registers 2). Forwards to LevelPlayRewardedAd.isAdReady()Z (:205 body
// iget mRewardedAd → invoke-virtual isAdReady). C# AndroidRewardedAd.IsAdReady
// (IL2CPP 0x436F378) synchronously JNI-calls THIS method inside
// <IsAdReady>b__52_0 (0x436FBE8) and stores the result into m_IsReady, so
// forcing true here is what the game actually reads.
object RewardedAdIsAdReadyFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "isAdReady",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "isAdReady",
        )
    )
)

// RewardedAd.isPlacementCapped(String)Z — public static. RewardedAd.smali:83
// (.registers 1). Forwards to LevelPlayRewardedAd.isPlacementCapped. Frequency
// capping must never deny a forged offer → forced false.
object RewardedAdIsPlacementCappedFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "isPlacementCapped",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "isPlacementCapped",
        )
    )
)

// RewardedAd.setupRewardedListener(IUnityRewardedAdListener)V — private.
// RewardedAd.smali:139 (.registers 4, p0=v2, p1=v3). Called from the bridge
// constructor (:15, invoke-direct with ctor param p3 = the C# proxy); wraps
// the proxy in RewardedAd$1 and registers it via LevelPlayRewardedAd
// .setListener. The listener is NOT kept as a RewardedAd field, so the patch
// stores it here (iput at index 0) for showAd/loadAd to fire callbacks with.
// Filter order matches smali: anonymous-listener <init> first, setListener
// second.
object RewardedAdSetupListenerFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "setupRewardedListener",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd\$1",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "setListener",
        )
    )
)

// RewardedAd.loadAd()V — public. RewardedAd.smali:223 (.registers 2).
// Forwards to LevelPlayRewardedAd.loadAd. The patch replaces the real load
// with a synthetic onAdLoaded so the C# loaded-state machine advances while
// no ad is ever fetched.
object RewardedAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "loadAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "loadAd",
        )
    )
)

// RewardedAd.showAd(String)V — public. RewardedAd.smali:279 (.registers 4;
// iget mRewardedAd → iget mActivity → LevelPlayRewardedAd.showAd(Activity,
// String)). THE reward choke point: the patch fires
// onAdDisplayed → onAdRewarded → onAdClosed through the captured proxy.
object RewardedAdShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "showAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "showAd",
        )
    )
)

// ---------------------------------------------------------------------------
// Interstitial ads (Target 2)
// ---------------------------------------------------------------------------

// InterstitialAd.isAdReady()Z — public. classes5/.../InterstitialAd.smali:185
// (.registers 2). Same JNI-read pattern as the rewarded twin; C#
// AndroidInterstitialAd.IsAdReady keeps a <IsAdReady>b__46_0 closure that
// queries this method.
object InterstitialAdIsAdReadyFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/InterstitialAd;",
    name = "isAdReady",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;",
            name = "isAdReady",
        )
    )
)

// InterstitialAd.isPlacementCapped(String)Z — public static.
// InterstitialAd.smali:83 (.registers 1). Forced false, symmetric with
// the rewarded bridge.
object InterstitialAdIsPlacementCappedFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/InterstitialAd;",
    name = "isPlacementCapped",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;",
            name = "isPlacementCapped",
        )
    )
)

// InterstitialAd.setupInterstitialListener(IUnityInterstitialAdListener)V —
// private. InterstitialAd.smali:139 (.registers 4, p0=v2, p1=v3). Wraps the
// C# proxy in InterstitialAd$1 → LevelPlayInterstitialAd.setListener. Same
// capture point as the rewarded bridge (ctor :15 invoke-direct).
object InterstitialAdSetupListenerFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/InterstitialAd;",
    name = "setupInterstitialListener",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/ironsource/unity/androidbridge/IUnityInterstitialAdListener;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/unity/androidbridge/InterstitialAd\$1",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;",
            name = "setListener",
        )
    )
)

// InterstitialAd.loadAd()V — public. InterstitialAd.smali:204 (.registers 2).
// Real load replaced by synthetic onAdLoaded (no ad fetched).
object InterstitialAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/InterstitialAd;",
    name = "loadAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;",
            name = "loadAd",
        )
    )
)

// InterstitialAd.showAd(String)V — public. InterstitialAd.smali:260
// (.registers 4; iget mInterstitialAd → iget mActivity →
// LevelPlayInterstitialAd.showAd(Activity, String)). Between-run interstitial
// choke point: fires onAdDisplayed → onAdClosed so any C# await-on-close flow
// completes.
object InterstitialAdShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/InterstitialAd;",
    name = "showAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;",
            name = "showAd",
        )
    )
)

// ---------------------------------------------------------------------------
// Banner ads (Target: display suppression)
// ---------------------------------------------------------------------------

// BannerAd.load()V — public. classes5/.../BannerAd.smali:1217 (.registers 2).
// Forwards to mBannerAdView.loadAd() (LevelPlayBannerAdView). Suppressing the
// fetch means the banner view never receives content; C# AdManager.LoadBanner
// has an explicit timeoutInSeconds parameter (dump.cs 856840) and a dedicated
// NoBannerLoadedException (857501), so the load wait ends gracefully.
object BannerAdLoadFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/BannerAd;",
    name = "load",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/banner/LevelPlayBannerAdView;",
            name = "loadAd",
        )
    )
)

// BannerAd.showAd()V — public. BannerAd.smali:1305 (.registers 3). Posts
// BannerAd$2 onto the UI thread, which flips mBannerAdView to VISIBLE
// (BannerAd$2.run: setVisibility(0) + mBannerAdViewVisibilityState = 0).
// Suppressed so the (empty) view is never revealed even with displayOnLoad.
// name + definingClass disambiguate from hideAd(), which also calls
// Activity.runOnUiThread.
object BannerAdShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/BannerAd;",
    name = "showAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "runOnUiThread",
        )
    )
)

// ---------------------------------------------------------------------------
// Unity Ads façade (Target 4 — defense in depth)
//
// The C# side never references com.unity3d.ads (zero string hits in dump.cs;
// the AndroidJavaProxy ad list is only com.ironsource.unity.androidbridge.*),
// so these overloads are reachable only through mediation adapters that our
// bridge cuts upstream. Patched anyway: each show is body-replaced with a
// call to UnityAds.morpheForgeShowComplete, which fires
// onUnityAdsShowComplete(placement, COMPLETED) — never a bare return, because
// a caller awaiting the completion callback would otherwise hang (same
// soft-lock class as RewardedAd.showAd).
//
// All four bodies contain `invoke-static UnityAdsImplementation.getInstance`
// → `invoke-interface IUnityAds.show(...)` (UnityAds.smali:1387/:1440/:1495/
// :1548); the parameter lists pin the overloads. Kotlin compiles these
// `public static final` — access flags list FINAL to match exactly.
// ---------------------------------------------------------------------------

// UnityAds.show(Activity, String)V — UnityAds.smali:1387 (.registers 5;
// p0=v3, p1=v4, locals v0-v2). Real listener argument is const/4 0x0; the
// patch passes a null listener to the forge helper (nothing to notify).
object UnityAdsShowNoListenerFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/ads/UnityAds;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/services/ads/IUnityAds;",
            name = "show",
        )
    )
)

// UnityAds.show(Activity, String, IUnityAdsShowListener)V —
// UnityAds.smali:1440 (.registers 5; p0=v2, p1=v3, p2=v4). Listener passed
// straight through to the forge helper.
object UnityAdsShowListenerFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/ads/UnityAds;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Lcom/unity3d/ads/IUnityAdsShowListener;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/services/ads/IUnityAds;",
            name = "show",
        )
    )
)

// UnityAds.show(Activity, String, UnityAdsShowOptions)V —
// UnityAds.smali:1495 (.registers 5; p0=v2, p1=v3, p2=v4). Original passes
// const/4 0x0 as the listener; patch mirrors that with a null forge target.
object UnityAdsShowOptionsFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/ads/UnityAds;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Lcom/unity3d/ads/UnityAdsShowOptions;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/services/ads/IUnityAds;",
            name = "show",
        )
    )
)

// UnityAds.show(Activity, String, UnityAdsShowOptions, IUnityAdsShowListener)V
// — UnityAds.smali:1548 (.registers 5; p0=v1, p1=v2, p2=v3, p3=v4, local v0).
// The 4-arg tail wraps listener callbacks in
// Utilities.wrapCustomerListener — our straight-line forge supersedes it.
object UnityAdsShowOptionsListenerFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/ads/UnityAds;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Lcom/unity3d/ads/UnityAdsShowOptions;",
        "Lcom/unity3d/ads/IUnityAdsShowListener;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/services/ads/IUnityAds;",
            name = "show",
        )
    )
)

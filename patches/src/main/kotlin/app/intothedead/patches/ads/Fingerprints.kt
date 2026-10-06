package app.intothedead.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Into the Dead 1 — v2.9.5 fingerprints.
 *
 * 2.9.5 UPGRADED the IronSource/LevelPlay Unity plugin and DELETED the entire
 * legacy Unity bridge (`AndroidBridge`, `AndroidBridge$1..$8`,
 * `LevelPlayRewardedVideoWrapper`, `LevelPlayInterstitialWrapper`,
 * `LevelPlayBannerWrapper` and every `UnityLevelPlay*Listener`).
 * IL2CPP `global-metadata.dat` confirms the C# side migrated in lockstep to the
 * new API only (`Unity.Services.LevelPlay.LevelPlayRewardedAd` /
 * `LevelPlayInterstitialAd` / `LevelPlayBannerAd`, with `IronSource.Agent`
 * wrapper types gone).
 *
 * Everything here therefore targets the surviving NEW-API bridge in
 * classes7/com/ironsource/unity/androidbridge/, or the untouched Google Unity
 * Ads App-Open bridge (which moved classes8 → classes7; fingerprints are
 * dex-agnostic so no change was needed for those).
 *
 * See analysis/intothedead2_build/notes/recon.md for the full dead/alive map.
 */

// ---------------------------------------------------------------------------
// Rewarded ads, instant grant (LevelPlay new-API bridge, classes7).
//
// The C# LevelPlayRewardedAd.ShowAd(placement) entry lands in RewardedAd.showAd.
// The patch fakes availability (isAdReady, isPlacementCapped, loadAd) and fires a
// synthetic displayed-rewarded-closed lifecycle in showAd, resolving the reward
// name and amount LIVE via getReward so no placement or reward-name constant is
// ever hardcoded.
// ---------------------------------------------------------------------------

// RewardedAd.isAdReady()Z — public. Forwards to LevelPlayRewardedAd.isAdReady.
// Smali: classes7/.../RewardedAd.smali (.registers 2).
// Single filter: only caller of LevelPlayRewardedAd.isAdReady in this class.
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

// RewardedAd.isPlacementCapped(String)Z — public static. Forwards to
// LevelPlayRewardedAd.isPlacementCapped. Smali: RewardedAd.smali (.registers 1,
// static so no `this` and no locals). Single filter: only caller of
// LevelPlayRewardedAd.isPlacementCapped here.
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

// RewardedAd.loadAd()V — public. Forwards to LevelPlayRewardedAd.loadAd.
// Smali: RewardedAd.smali (.registers 2).
// Single filter: only caller of LevelPlayRewardedAd.loadAd in this class.
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

// RewardedAd.setupRewardedListener(IUnityRewardedAdListener)V — private.
// Called from the constructor with the C# proxy as p1; wraps it in RewardedAd$1
// and registers it via LevelPlayRewardedAd.setListener. The patch stores the proxy
// in a new instance field here so showAd can fire the lifecycle directly.
// Smali: RewardedAd.smali (.registers 4). Filter order matches smali:
// anonymous listener build first, then setListener.
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

// RewardedAd.showAd(String)V — public. Forwards to
// LevelPlayRewardedAd.showAd(Activity, String). Smali: RewardedAd.smali
// (.registers 4 — v0, v1 locals + p0, p1).
// Single filter: only caller of LevelPlayRewardedAd.showAd in this class.
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
// Interstitial removal (LevelPlay new-API bridge, classes7).
//
// loadAd + showAd never reach the SDK, isAdReady always false, and showAd fires a
// synthetic onAdDisplayed → onAdClosed on the C# proxy so the game's interstitial
// state machine always resolves instead of waiting for a close that never comes.
// ---------------------------------------------------------------------------

// InterstitialAd.loadAd()V — public. Forwards to LevelPlayInterstitialAd.loadAd.
// Smali: InterstitialAd.smali (.registers 2).
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

// InterstitialAd.isAdReady()Z — public. Forwards to
// LevelPlayInterstitialAd.isAdReady. Smali: InterstitialAd.smali (.registers 2).
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

// InterstitialAd.setupInterstitialListener(IUnityInterstitialAdListener)V — private.
// Interstitial counterpart of RewardedAd.setupRewardedListener: called from the
// constructor with the C# proxy as p1, wraps it in InterstitialAd$1 and registers
// it via LevelPlayInterstitialAd.setListener. Stores the proxy in a new instance
// field for the showAd synthetic lifecycle.
// Smali: InterstitialAd.smali (.registers 4).
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

// InterstitialAd.showAd(String)V — public. Forwards to
// LevelPlayInterstitialAd.showAd(Activity, String).
// Smali: InterstitialAd.smali (.registers 4 — v0, v1 locals + p0, p1).
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
// Banner removal (LevelPlay new-API bridge, classes7).
// ---------------------------------------------------------------------------

// BannerAd.load()V — public. Forwards to LevelPlayBannerAdView.loadAd.
// Smali: BannerAd.smali (.registers 2 — v0 local + p0).
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

// BannerAd.showAd()V — public. Posts BannerAd$2 on the UI thread to set view
// visibility. Smali: BannerAd.smali (.registers 3 — v0, v1 locals + p0).
// Filter: showAd() is the only method building BannerAd$2.
object BannerAdShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/BannerAd;",
    name = "showAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/unity/androidbridge/BannerAd\$2",
            name = "<init>",
        )
    )
)

// ---------------------------------------------------------------------------
// App Open Ad removal (Google Unity Ads bridge, classes7 in 2.9.5 — was classes8).
// ---------------------------------------------------------------------------

// UnityAppOpenAd.loadAd(String, AdRequest)V — public. PRIMARY App Open Ad load
// entry (C# `LoadAppOpenAd` → JNI). Posts UnityAppOpenAd$$ExternalSyntheticLambda0
// to the UI thread which performs the real `AppOpenAd.load(...)`.
// Smali: UnityAppOpenAd.smali (.registers 5). No-op kills it at the source, so
// `isAdAvailable()` stays false and the C# show gate skips.
object UnityAppOpenAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "loadAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/unity/ads/UnityAppOpenAd\$\$ExternalSyntheticLambda0",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

// UnityAppOpenAd.show()V — public. PRIMARY direct show entry. Null-checks
// appOpenAd, then posts UnityAppOpenAd$$ExternalSyntheticLambda1 which calls the
// real `AppOpenAd.show(activity)`. Smali: UnityAppOpenAd.smali (.registers 3).
object UnityAppOpenAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/unity/ads/UnityAppOpenAd\$\$ExternalSyntheticLambda1",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

// UnityAppOpenAd.pollAd(String)V — public. SECONDARY AdMob preloader path.
// `AppOpenAd.pollAd(ctx, adUnitId)` can populate appOpenAd WITHOUT loadAd.
// Smali: UnityAppOpenAd.smali (.registers 9 — v0..v6 locals + p0, p1).
// No-op leaves the branches unreachable dead code, which the verifier accepts
// (matches the repo's existing no-op convention).
object UnityAppOpenAdPollAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "pollAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/appopen/AppOpenAd",
            name = "pollAd",
        )
    )
)

// UnityAppStateEventNotifier.startListening()V — public. SECONDARY auto-show
// trigger kill: registers this notifier as a ProcessLifecycleOwner observer, so
// every foreground onStart() fires `onAppStateChanged(false)` → C# auto-shows the
// App Open Ad. No-op = the notifier is never registered, so the auto-trigger
// never arms. Smali: UnityAppStateEventNotifier.smali (.registers 3).
object UnityAppStateEventNotifierStartListeningFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppStateEventNotifier;",
    name = "startListening",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/unity/ads/UnityAppStateEventNotifier\$1",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Landroid/app/Activity",
            name = "runOnUiThread",
        ),
    )
)

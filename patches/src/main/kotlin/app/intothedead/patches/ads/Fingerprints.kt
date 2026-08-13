package app.intothedead.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.showRewardedVideo(String)V — public.
 *
 * C# legacy show entry (`IronSource.Agent.ShowRewardedVideo(placement)`). It simply
 * forwards to the SDK: `invoke-static {p1}, IronSource.showRewardedVideo(String)V`.
 * This is the PRIMARY grant trigger — the game's C# hooks `OnRewardedVideoRewarded`.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:1618
 * (.registers 2 — no locals, so the patch expands registers via cloneParameters()).
 *
 * Filter (exact instruction order): only this method calls
 * IronSource.showRewardedVideo(String); the parameterless
 * showRewardedVideo()V variant calls the no-arg IronSource overload.
 */
object ShowRewardedVideoFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "showRewardedVideo",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showRewardedVideo",
            parameters = listOf("Ljava/lang/String;"),
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.showRewardedVideo()V — public (parameterless).
 *
 * The no-placement legacy show entry (`IronSource.Agent.ShowRewardedVideo()` with no
 * placement — the game uses it for flows where the placement string is optional). It simply
 * forwards to the SDK: `invoke-static {}, IronSource.showRewardedVideo()V`. Patched exactly
 * like the String variant, but the placement name is always "REWARDED_VIDEO" (a real constant
 * in the game's handler match list).
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:1609
 * (.registers 1 — p0 only, ZERO locals, so the patch expands registers via
 * cloneMutable(additionalRegisters = 4) → registers 5: v0-v3 locals + p0).
 *
 * Filter with parameters = listOf() disambiguates the parameterless overload: it calls
 * IronSource.showRewardedVideo()V (no-arg), while showRewardedVideo(String)V calls the
 * String overload.
 */
object ShowRewardedVideoNoArgFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "showRewardedVideo",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showRewardedVideo",
            parameters = listOf(),
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.RewardedAd.showAd(String)V — public.
 *
 * NEW-API show entry (`LevelPlayRewardedAd.ShowAd(placementName)` in C#). It forwards to
 * `LevelPlayRewardedAd.showAd(Activity, String)`. Secondary grant trigger
 * (game events `onAdRewardedEvent` / `IUnityRewardedAdListener` are wired as well).
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/RewardedAd.smali:134
 * (.registers 4 — v0, v1 locals + p0, p1 params).
 *
 * Filter: the only call site of LevelPlayRewardedAd.showAd in this bridge class.
 */
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

/**
 * com.ironsource.unity.androidbridge.RewardedAd.setupRewardedListener(IUnityRewardedAdListener)V — private.
 *
 * Called from BOTH RewardedAd constructors with the C# proxy (`IUnityRewardedAdListener`)
 * as p1. It wraps the proxy in the anonymous RewardedAd$1 SDK listener and registers it with
 * `LevelPlayRewardedAd.setListener(...)`. The patch stores the proxy in a new instance field
 * here so showAd can fire the reward lifecycle directly.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/RewardedAd.smali:80
 * (.registers 4 — v0, v1 locals + p0, p1 params).
 *
 * Filter: the only call site of LevelPlayRewardedAd.setListener in this bridge class.
 */
object RewardedAdSetupListenerFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/RewardedAd;",
    name = "setupRewardedListener",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/unity3d/mediation/rewarded/LevelPlayRewardedAd;",
            name = "setListener",
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.LevelPlayInterstitialWrapper.onAdReady(AdInfo)V — public.
 *
 * The SDK → C# "interstitial is loaded and ready" dispatch (legacy LevelPlay path). It
 * posts LevelPlayInterstitialWrapper$1 (which calls the C# proxy's onAdReady) to a
 * background task. Disabling this prevents the C# InterstitialManager from ever
 * transitioning to show — the PRIMARY ready-signal kill (no launch ad, no mid-game ad,
 * no PauseGame → no hang).
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper.smali:112
 * (.registers 3 — v0 local + p0, p1 params).
 *
 * Filter order matches smali exactly: new-instance $1 → invoke-direct $1.<init> (line 123)
 * THEN postBackgroundTask (line 125). onAdReady(AdInfo) is unique vs the SDK's own
 * internal listeners because it dispatches through the Unity bridge's $1 runnable.
 */
object LevelPlayInterstitialWrapperOnAdReadyFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper;",
    name = "onAdReady",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/ironsource/mediationsdk/adunit/adapter/utility/AdInfo;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/unity/androidbridge/LevelPlayInterstitialWrapper\$1",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridgeUtilities",
            name = "postBackgroundTask",
        ),
    )
)

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.isInterstitialReady()Z — public.
 *
 * C# `IsInterstitialReady()` poll. Returns the SDK's ready state by delegating to
 * `IronSource.isInterstitialReady()Z`. Forcing false means the C# manager never
 * believes an interstitial exists (defense for the auto-load path).
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:821
 * (.registers 2 — v0 local + p0).
 *
 * Filter: the only bridge method calling IronSource.isInterstitialReady. IronSource's
 * isInterstitialReady()Z has a single no-arg overload, so the filter is unambiguous.
 */
object AndroidBridgeIsInterstitialReadyFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "isInterstitialReady",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "isInterstitialReady",
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.loadInterstitial()V — public.
 *
 * C# `CacheInterstitial` → `LoadInterstitial()`. No-op saves the explicit network load.
 * NOTE: the SDK auto-loads after init (no setManualLoadInterstitial in this build), so
 * this is NOT sufficient alone — paired with the ready-kill + ready=false + show-kill.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:910
 * (.registers 1 — p0 only, no locals).
 *
 * Filter: the only bridge method calling IronSource.loadInterstitial.
 */
object AndroidBridgeLoadInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "loadInterstitial",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "loadInterstitial",
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.showInterstitial()V — public (parameterless).
 *
 * C# legacy show entry without placement. Normally forwards to
 * `IronSource.showInterstitial()V`. Instead we fire the synthetic lifecycle
 * onAdOpened("") → onAdShowSucceeded("") → onAdClosed("") on the C# proxy so the game
 * unpauses/continues immediately, falling back to the real show only if the bridge
 * wrapper or listener is unset.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:1591
 * (.registers 1 — p0 only, ZERO locals; the patch must expand registers via
 * cloneMutable(additionalRegisters = 2) → registers 3: v0, v1 locals + p0).
 *
 * Filter with parameters = listOf() disambiguates the parameterless overload: it calls
 * IronSource.showInterstitial()V (no-arg), while showInterstitial(String)V calls the
 * String overload.
 */
object AndroidBridgeShowInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "showInterstitial",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showInterstitial",
            parameters = listOf(),
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.showInterstitial(String)V — public.
 *
 * C# legacy show entry WITH placement (`GetInterstitial` → `ShowInterstitial(placement)`).
 * Normally forwards to `IronSource.showInterstitial(String)V`. Replaced with the same
 * synthetic close-lifecycle dispatch as the parameterless variant.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:1600
 * (.registers 2 — p0, p1, ZERO locals; the patch expands registers via cloneParameters() →
 * registers 4: v0, v1 locals + p0, p1).
 *
 * Filter with parameters = listOf("Ljava/lang/String;") disambiguates the String overload.
 */
object AndroidBridgeShowInterstitialStringFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "showInterstitial",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showInterstitial",
            parameters = listOf("Ljava/lang/String;"),
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.AndroidBridge.loadBanner(
 *   String, int, int, int, String, boolean, boolean, float, float)V — public.
 *
 * C# `LoadBanner` (PikPok Advertising.LoadBanner/GetExistingBanner). No-op = the banner
 * view is never created → nothing displays. displayBanner/hideBanner/destroyBanner are
 * all null-safe (verified: AndroidBridge$6/$7/$8 run() null-check mBannerContainer
 * before touching the banner and wrap in try/catch), so no downstream NPE.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/AndroidBridge.smali:867
 * (.registers 12 — v0, v1 locals + p0..p9 params). The body monitor-guards, sets
 * mIsBannerLoadCalled, then invokes the private loadAndShowBanner (line 893).
 *
 * Filter: loadBanner is the ONLY caller of the private loadAndShowBanner (verified:
 * single invoke-direct/range at line 893), so that call uniquely identifies this method.
 * (NOTE: the notes suggested AndroidBridge$5.<init> + Handler.post — those live inside
 * loadAndShowBanner, NOT in loadBanner itself — this filter is the correct one.)
 */
object AndroidBridgeLoadBannerFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
    name = "loadBanner",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;", "I", "I", "I", "Ljava/lang/String;",
        "Z", "Z", "F", "F",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/unity/androidbridge/AndroidBridge;",
            name = "loadAndShowBanner",
        )
    )
)

/**
 * com.ironsource.unity.androidbridge.InterstitialAd.loadAd()V — public.
 *
 * NEW-API load entry (`Unity.Services.LevelPlay.LevelPlayInterstitialAd` in C#). Forwards
 * to `LevelPlayInterstitialAd.loadAd()`. No-op = never loads via the new API.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/InterstitialAd.smali:123
 * (.registers 2 — v0 local + p0).
 */
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

/**
 * com.ironsource.unity.androidbridge.InterstitialAd.isAdReady()Z — public.
 *
 * NEW-API readiness poll. Forcing false keeps the C# LevelPlayInterstitialAd manager from
 * believing a loaded ad exists.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/InterstitialAd.smali:110
 * (.registers 2 — v0 local + p0).
 */
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

/**
 * com.ironsource.unity.androidbridge.InterstitialAd.showAd(String)V — public.
 *
 * NEW-API show entry. Forwards to `LevelPlayInterstitialAd.showAd(Activity, String)`.
 * No-op = never displays via the new API. (No synthetic close-callback: the game's
 * InterstitialManager drives the legacy provider; if a future test shows the new API in
 * use, upgrade to a synthetic onAdDisplayed→onAdClosed with a stored listener field.)
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/InterstitialAd.smali:134
 * (.registers 4 — v0, v1 locals + p0, p1).
 *
 * Filter: the only bridge method calling LevelPlayInterstitialAd.showAd.
 */
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

/**
 * com.ironsource.unity.androidbridge.BannerAd.load()V — public.
 *
 * NEW-API banner load (`LevelPlayBannerAdView`). No-op = new-API banner never loads.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/BannerAd.smali:770
 * (.registers 2 — v0 local + p0).
 */
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

/**
 * com.ironsource.unity.androidbridge.BannerAd.showAd()V — public.
 *
 * NEW-API banner show. Posts BannerAd$2 on the UI thread to set view visibility. No-op =
 * an empty banner view never occupies layout.
 *
 * Confirmed smali: classes7/com/ironsource/unity/androidbridge/BannerAd.smali:803
 * (.registers 3 — v0, v1 locals + p0).
 *
 * Filter: showAd() is the only method building BannerAd$2.
 */
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

/**
 * com.google.unity.ads.UnityAppOpenAd.loadAd(String, AdRequest)V — public.
 *
 * PRIMARY App Open Ad load entry. C# `LoadAppOpenAd` (PikPok.Advertising) →
 * JNI → this method. Normally posts UnityAppOpenAd$$ExternalSyntheticLambda0
 * to the UI thread which calls `AppOpenAd.load(...)` (the real network load).
 * No-op = nothing ever loads → `isAdAvailable()` false → the C# `ShowAppOpenAd`
 * gate skips → `show()` hits its null-guard. Kills the ad at the source.
 *
 * Confirmed smali: classes8/com/google/unity/ads/UnityAppOpenAd.smali:309
 * (.registers 5 — v0, v1 locals + p0, p1, p2 params; no try/catch/monitor).
 * The body (lines 323-329) is: iget activity → new-instance $$ExternalSyntheticLambda0
 * → invoke-direct $$ExternalSyntheticLambda0.<init> → runOnUiThread.
 *
 * Filter (order matches smali exactly): the $$ExternalSyntheticLambda0 build is
 * unique to loadAd (grep-verified: no other method constructs it), and
 * Activity.runOnUiThread is the final call. NOTE: the notes' suggested
 * self-call `methodCall(UnityAppOpenAd, loadAd)` does NOT exist in the body —
 * this filter pair is the verified reality.
 */
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

/**
 * com.google.unity.ads.UnityAppOpenAd.show()V — public.
 *
 * PRIMARY direct show entry. C# `ShowAppOpenAd` → JNI → this method. Normally
 * null-checks appOpenAd, then posts UnityAppOpenAd$$ExternalSyntheticLambda1 to
 * the UI thread which calls `AppOpenAd.show(activity)` — the REAL full-screen
 * display. No-op = even if some path (e.g. a late load completing) produces a
 * non-null appOpenAd, show() never displays it. Belt-and-braces on loadAd.
 *
 * Confirmed smali: classes8/com/google/unity/ads/UnityAppOpenAd.smali:414
 * (.registers 3 — v0, v1 locals + p0; null-guard branch + UI-thread post, no
 * try/catch/monitor). The display branch (lines 433-439) is: iget activity →
 * new-instance $$ExternalSyntheticLambda1 → invoke-direct <init> → runOnUiThread.
 *
 * Filter (order matches smali exactly): the $$ExternalSyntheticLambda1 build is
 * unique to show() (grep-verified), followed by Activity.runOnUiThread. No other
 * show method exists (no overloads), and parameters = listOf() pins the no-arg
 * form.
 */
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

/**
 * com.google.unity.ads.UnityAppOpenAd.pollAd(String)V — public.
 *
 * SECONDARY AdMob preloader path. `AppOpenAd.pollAd(ctx, adUnitId)` can populate
 * the appOpenAd field WITHOUT loadAd (Google Play Services preloaded cache).
 * No-op = the preloader path can never supply an ad, closing the one route that
 * could still populate appOpenAd after loadAd is disabled.
 *
 * Confirmed smali: classes8/com/google/unity/ads/UnityAppOpenAd.smali:334
 * (.registers 9 — v0..v6 locals + p0, p1 params; if-nez branches, NO try/catch —
 * a single return-void at index 0 leaves the branches unreachable dead code,
 * which the verifier accepts; matches the repo's loadBanner no-op convention).
 *
 * Filter: pollAd() is the ONLY method calling AppOpenAd.pollAd (grep-verified
 * single invoke-static at line 348), so one filter is unambiguous.
 */
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

/**
 * com.google.unity.ads.UnityAppStateEventNotifier.startListening()V — public.
 *
 * SECONDARY auto-show trigger kill. Called by C# to register this notifier as a
 * ProcessLifecycleOwner observer. Normally posts UnityAppStateEventNotifier$1 to
 * the UI thread; $1.run() calls ProcessLifecycleOwner.get().getLifecycle().
 * addObserver(this), and every subsequent onStart() fires
 * `onAppStateChanged(false)` → C# ShowAppOpenAd auto-fires on foreground.
 * No-op = the notifier is never registered → onStart/onStop lifecycle callbacks
 * never reach C# → the app-open show never auto-triggers. Covers any future C#
 * change. No side effects: this notifier exists only for App-Open ads.
 *
 * Confirmed smali: classes8/com/google/unity/ads/UnityAppStateEventNotifier.smali:141
 * (.registers 3 — v0, v1 locals + p0; no try/catch/monitor). The body (lines
 * 145-151) is: iget activity → new-instance UnityAppStateEventNotifier$1 →
 * invoke-direct $1.<init> → runOnUiThread.
 *
 * Filter (order matches smali exactly): the UnityAppStateEventNotifier$1 build
 * is unique to startListening (stopListening builds $2 — grep-verified), followed
 * by Activity.runOnUiThread. NOTE: the notes' suggested
 * `methodCall(ProcessLifecycleOwner, get)` lives INSIDE $1.run(), not in
 * startListening — this filter pair is the verified reality.
 */
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


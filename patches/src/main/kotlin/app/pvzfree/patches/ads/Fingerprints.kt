package app.pvzfree.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * GluAds ironSource rewarded-video manager (classes5.dex) — six entry points.
 *
 * Every target below lives in the unobfuscated `csdk.gluads` package, so the class
 * descriptor + method name + signature uniquely identify the method. No instruction
 * filters are needed and none are declared; nothing here touches `instructionMatches`.
 *
 * Shared descriptor: `Lcsdk/gluads/ironsource/EAIronSourceRVManager;`
 * Shared parameter list: `(String adType, String placement, Map extra)` where the
 * third parameter is `Ljava/util/Map;`.
 */

/**
 * `EAIronSourceRVManager.show(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V`
 * — classes5.dex `csdk/gluads/ironsource/EAIronSourceRVManager.smali:887`
 * (`.registers 16`, public; p0=v13, p1=adType, p2=placement, p3=extra).
 *
 * Normally checks the frequency cap, calls `isLoaded`, then
 * `LevelPlayRewardedAd.showAd(...)` after `sendPlacementEvent("showStarted")`. This is
 * the rewarded-video display choke point that we short-circuit.
 */
object RvShowFingerprint : Fingerprint(
    definingClass = "Lcsdk/gluads/ironsource/EAIronSourceRVManager;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;"
    )
)

/**
 * `EAIronSourceRVManager.load(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V`
 * — `EAIronSourceRVManager.smali:660` (`.registers 7`, public; p0=v4, p1=v5, p2=v6).
 *
 * Real load path for the rewarded ad unit. We report `loadFinished` immediately so the
 * native "watch ad" button is driven without touching the network.
 */
object RvLoadFingerprint : Fingerprint(
    definingClass = "Lcsdk/gluads/ironsource/EAIronSourceRVManager;",
    name = "load",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;"
    )
)

/**
 * `EAIronSourceRVManager.isLoaded(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)Z`
 * — `EAIronSourceRVManager.smali:602` (`.registers 4`, public; p0=v1, p1=v2, p2=v3).
 *
 * Reads `rewardedAd.isAdReady()` plus frequency-cap state. Forced to `true` so the
 * native layer always believes a rewarded video is available.
 */
object RvIsLoadedFingerprint : Fingerprint(
    definingClass = "Lcsdk/gluads/ironsource/EAIronSourceRVManager;",
    name = "isLoaded",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;"
    )
)

/**
 * `EAIronSourceInterstitialManager.show(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V`
 * — `EAIronSourceInterstitialManager.smali:347` (`.registers 6`, public; p0=v3, p1=v4,
 * p2=v5, p3 is the third user register at the end of the parameter list).
 *
 * Interstitial display choke point; normally gates on `isLoaded` then calls
 * `LevelPlayInterstitialAd.showAd(...)`. Short-circuited to emit only the placement
 * lifecycle events.
 */
object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcsdk/gluads/ironsource/EAIronSourceInterstitialManager;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;"
    )
)

/**
 * `EAIronSourceInterstitialManager.load(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V`
 * — `EAIronSourceInterstitialManager.smali:210` (`.registers 6`, public; same mapping).
 *
 * Interstitial load; reports `loadFinished` immediately and returns.
 */
object InterstitialLoadFingerprint : Fingerprint(
    definingClass = "Lcsdk/gluads/ironsource/EAIronSourceInterstitialManager;",
    name = "load",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;"
    )
)

/**
 * `EAIronSourceInterstitialManager.isLoaded(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)Z`
 * — `EAIronSourceInterstitialManager.smali:166` (`.registers 4`, public; p0=v1, p1=v2,
 * p2=v3).
 *
 * Forced to `true` so the native scheduler thinks an interstitial is ready (it is then
 * short-circuited by [InterstitialShowFingerprint], so nothing is ever displayed).
 */
object InterstitialIsLoadedFingerprint : Fingerprint(
    definingClass = "Lcsdk/gluads/ironsource/EAIronSourceInterstitialManager;",
    name = "isLoaded",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/Map;"
    )
)

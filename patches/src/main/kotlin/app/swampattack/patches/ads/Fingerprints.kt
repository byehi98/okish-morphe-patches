package app.swampattack.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Swamp Attack 4.8.7.0 (versionCode 724) — ad-layer fingerprints ─────────
//
// Game package `com.libo7.swampattack.*` ships UNOBFUSCATED, so names below are
// the literal smali names; every fingerprint is still anchored on more than the
// name — filters pin calls/strings that co-occur in exactly one method.
//
// Verified against (fixedcheck tree, original names, 17 dex dirs):
//   smali_classes7/com/libo7/swampattack/ads/AdManager.smali
//   smali_classes7/com/libo7/swampattack/ads/controller/RewardedAdController.smali
//   smali_classes7/com/libo7/swampattack/NativeInterface.smali

/**
 * `AdManager.areAdsPermanentlyRemoved(Context)Z` — jadx `AdManager.java:709`,
 * `private final`.
 *
 * Reads SharedPreferences `prefs` key `Ads.permanentlyRemoved` and is the app's OWN
 * single gate for forced (interstitial) advertising — every call site branches on it:
 *
 * * `loadInterstitial` → early return (never loads)
 * * `showInterstitial` → early return (never shows)
 * * `onResume` → skips the interstitial reload (rewarded reload still runs)
 *
 * It is written from `NativeInterface` (jadx line 264) only when native reports a
 * purchase granted ad removal AND remote config `timed_ad_removal_on_purchase` is off —
 * i.e. normally reachable only after a real purchase. Forcing the return value to
 * `true` reproduces "permanent remove-ads" without touching any of the 10+ mediation
 * SDKs (AppLovin MAX primary; AdMob, Meta, Unity Ads, Pangle, InMobi, Vungle,
 * IronSource, Mintegral, Chartboost, HyprMX adapters).
 *
 * Filters pin `Context.getSharedPreferences` + `SharedPreferences.getBoolean`, the
 * two calls that co-occur in exactly this method of `AdManager`.
 */
object AreAdsPermanentlyRemovedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    definingClass = "Lcom/libo7/swampattack/ads/AdManager;",
    name = "areAdsPermanentlyRemoved",
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/Context;",
            name = "getSharedPreferences",
        ),
        string("Ads.permanentlyRemoved"),
    ),
)

/**
 * `RewardedAdController.isAdReady()Z` — smali `RewardedAdController.smali:946`,
 * `public final`.
 *
 * The single "is a rewarded ad available?" gate of the whole rewarded flow:
 *
 * ```
 * AdManager.isRewardedReady()          AdManager.smali:1259
 *   └→ NativeInterface.isRewardedAdReady()   NativeInterface.smali:1559  (native polls)
 * RewardedAdController.show()          RewardedAdController.smali:1272   (early-exit "Not ready")
 * AdManager$setupAdManagers$2.onAdShowFailed  (analytics flag only)
 * ```
 *
 * Body: `Companion.isInitialized()` AND `MeticaAds.isRewardedReady(adUnitId)` —
 * both calls co-occur in exactly this method (the `Z` return type vs `V` keeps
 * it unambiguous with the structurally-similar `load()` below, which carries a
 * third `MeticaAds.loadRewarded` call).
 */
object RewardedAdIsReadyFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    definingClass = "Lcom/libo7/swampattack/ads/controller/RewardedAdController;",
    name = "isAdReady",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/libo7/swampattack/ads/AdManager\$Companion;",
            name = "isInitialized",
        ),
        methodCall(
            definingClass = "Lcom/metica/ads/MeticaAds;",
            name = "isRewardedReady",
        ),
    ),
)

/**
 * `RewardedAdController.load()V` — smali `RewardedAdController.smali:980`,
 * `public final`.
 *
 * Preloads the next rewarded ad and — crucially — is the ONLY path that notifies
 * the native game a rewarded ad is watchable:
 *
 * ```
 * load()
 *   ├→ !Companion.isInitialized()   → return
 *   ├→ Companion.isAdShowing()      → return ("load skipped: an ad is currently showing")
 *   ├→ MeticaAds.isRewardedReady()  → listener.onAdLoaded("applovin")
 *   │                                  └→ native_libO7_RewardedVideoAdReady()  (JNI, ready signal)
 *   └→ MeticaAds.loadRewarded(adUnitId, this)  + 30s loadWatchdog + retry backoff
 * ```
 *
 * Filters pin the three calls in exact smali order:
 * `Companion.isInitialized` (line 986) → `MeticaAds.isRewardedReady` (line 1021)
 * → `MeticaAds.loadRewarded` (line 1087). `instructionMatches[1]` is therefore the
 * `isRewardedReady` invoke-interface; the `move-result v0` right after it is
 * replaced with `const/4 v0, 0x1` to force the "already ready" branch.
 */
object RewardedAdLoadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    definingClass = "Lcom/libo7/swampattack/ads/controller/RewardedAdController;",
    name = "load",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/libo7/swampattack/ads/AdManager\$Companion;",
            name = "isInitialized",
        ),
        methodCall(
            definingClass = "Lcom/metica/ads/MeticaAds;",
            name = "isRewardedReady",
        ),
        methodCall(
            definingClass = "Lcom/metica/ads/MeticaAds;",
            name = "loadRewarded",
        ),
    ),
)

/**
 * `RewardedAdController.show(Activity, String)V` — smali `RewardedAdController.smali:1260`,
 * `public final`. The ONE choke point every rewarded-ad show passes through:
 *
 * ```
 * native game (Marmalade/SDL, liblibO7.so)
 *   → NativeInterface.showRewardedAd(placement)          NativeInterface.smali:2398
 *     → AdManager.showRewarded(activity, placement)      AdManager.smali:1644 (posts to UI thread)
 *       → RewardedAdController.show(activity, placement) ← THIS METHOD (already on UI thread)
 *         ├→ !isAdReady() → listener.onAdShowFailed("applovin", "Not ready")  ← vanilla hang risk
 *         └→ MeticaSdk.getAds().showRewarded(activity, adUnitId, placement, null, this)
 *              callbacks: onAdShowSuccess → listener.onAdShown
 *                         onAdRewarded    → rewardEarned = true
 *                         onAdHidden      → libO7.native_libO7_OnAdFinished(rewardEarned, json)  ← reward credited HERE (JNI)
 *                                           listener.onAdHidden → load()
 * ```
 *
 * `native_libO7_OnAdFinished(ZLjava/lang/String;)V` (libO7.smali:304) is the
 * single native credit/unblock point — called exactly once per show (success or
 * failure path), so any replacement MUST invoke it exactly once with `true` or
 * the native flow hangs / never credits.
 *
 * Filters pin, in smali order: the two Intrinsics param-name strings `"activity"`
 * / `"placement"`, the `isAdReady()` gate, the `"Showing rewarded ad with
 * placement: "` log string, and the `MeticaAds.showRewarded` invoke-interface —
 * a combination unique to this method in the whole APK.
 */
object RewardedAdShowFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    definingClass = "Lcom/libo7/swampattack/ads/controller/RewardedAdController;",
    name = "show",
    filters = listOf(
        string("activity"),
        string("placement"),
        methodCall(
            definingClass = "Lcom/libo7/swampattack/ads/controller/RewardedAdController;",
            name = "isAdReady",
        ),
        string("Showing rewarded ad with placement: "),
        methodCall(
            definingClass = "Lcom/metica/ads/MeticaAds;",
            name = "showRewarded",
        ),
    ),
)

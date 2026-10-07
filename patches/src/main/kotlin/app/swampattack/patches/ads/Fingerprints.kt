package app.swampattack.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Swamp Attack 4.8.7.0 (versionCode 724) — ad-gate fingerprints ──────────

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

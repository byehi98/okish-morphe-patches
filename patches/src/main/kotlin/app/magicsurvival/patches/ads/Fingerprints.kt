package app.magicsurvival.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Mobile Ads Unity bridge (com.google.unity.ads, classes2.dex) ──────
//
// Magic Survival loads ads through the official Google Mobile Ads Unity plugin:
// the C# side holds `Interstitial` / `UnityAppOpenAd` / `UnityRewardedAd` /
// `UnityRewardedInterstitialAd` / `Banner` objects over JNI and waits on the
// `Unity*Callback` interfaces, which for a Unity build are AndroidJavaProxies
// bridging back into C#. Every one of those callbacks extends
// `UnityFullScreenContentCallback`, so firing showed/impression/dismissed
// (and onUserEarnedReward for rewarded formats) directly on the stored
// `callback` field resolves the game's ad state machine with no creative.
//
// Register budgets are documented next to each fingerprint; all of them fit
// the existing method except the two rewarded `show()` bodies, which are
// expanded with cloneMutable(+2) in the patch.

// ── Interstitial ─────────────────────────────────────────────────────────────

// Interstitial.loadAd(String, AdRequest): void — .registers 5.
// No-op: the ad is never requested, so isAdAvailable() stays false and the C#
// show gate skips the interstitial entirely (no network, no impression).
object InterstitialLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "loadAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
)

// Interstitial.pollAd(String): void — .registers 9.
// No-op: closes the AdMob preloaded-cache route that bypasses loadAd.
object InterstitialPollAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "pollAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

// Interstitial.show(): void — .registers 3 (v0, v1 + p0).
// Insurance: if the game calls show() anyway, fire the synthetic
// showed → impression → dismissed lifecycle so it never waits on a close
// event that can no longer arrive.
object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "show",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
)

// ── App open ─────────────────────────────────────────────────────────────────

// UnityAppOpenAd.loadAd(String, AdRequest): void — .registers 5.
// No-op: the format auto-shows on foreground, so killing the load is what
// keeps it off screen; isAdAvailable() stays false.
object UnityAppOpenAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "loadAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
)

// UnityAppOpenAd.pollAd(String): void — .registers 9.
object UnityAppOpenAdPollAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "pollAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

// UnityAppOpenAd.show(): void — .registers 3 (v0, v1 + p0).
object UnityAppOpenAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "show",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
)

// ── Banner ───────────────────────────────────────────────────────────────────

// Banner.loadAd(AdRequest): void — no-op: nothing is ever fetched for the view
// created by Banner.create (which stays GONE, so hide()/layout calls keep
// working against a non-null AdView).
object BannerLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner;",
    name = "loadAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Lcom/google/android/gms/ads/AdRequest;"),
)

// Banner.show(): void — .registers 3. No-op: the only call that flips the
// banner view's visibility to VISIBLE, so without it no banner is ever drawn.
object BannerShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner;",
    name = "show",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
)

// ── Rewarded (instant grants) ────────────────────────────────────────────────

// UnityRewardedAd.isAdAvailable(String): boolean — .registers 3.
// Always true, so the C# "can I show a rewarded ad?" gate passes offline.
object UnityRewardedAdIsAdAvailableFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "isAdAvailable",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
)

// UnityRewardedAd.loadAd(String, AdRequest): void — .registers 5.
// Reports onRewardedAdLoaded() straight away: no creative is fetched, but the
// C# side raises its "rewarded ready" event as if one had been.
object UnityRewardedAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "loadAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
)

// UnityRewardedAd.pollAd(String): void — .registers 9.
// Same faked "loaded" for the AdMob preloader path (bypasses loadAd).
object UnityRewardedAdPollAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "pollAd",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

// UnityRewardedAd.show(): void — .registers 3 → cloneMutable(+2) → 5
// (v0..v3 locals + p0). Fakes showed → reward → dismissed.
object UnityRewardedAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "show",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
)

// UnityRewardedAd.getRewardItem(): RewardItem — .registers 7.
// Reads the ad object we deliberately never populate, so it would hand C# a
// null (NPE risk on the proxy side); answer with RewardItem.DEFAULT_REWARD
// (type "", amount 1) instead.
object UnityRewardedAdGetRewardItemFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "getRewardItem",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/google/android/gms/ads/rewarded/RewardItem;",
    parameters = emptyList(),
)

// ── Rewarded interstitial (show path only) ───────────────────────────────────
//
// loadAd is deliberately LEFT REAL for this format: unlike UnityRewardedAd it
// is an interstitial-family placement, and faking "ready" on a format the
// game may auto-show when ready risks a load→show→load feedback loop.

// UnityRewardedInterstitialAd.show(): void — .registers 3 → cloneMutable(+2) → 5.
object UnityRewardedInterstitialAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "show",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
)

// UnityRewardedInterstitialAd.getRewardItem(): RewardItem — .registers 7.
object UnityRewardedInterstitialAdGetRewardItemFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "getRewardItem",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/google/android/gms/ads/rewarded/RewardItem;",
    parameters = emptyList(),
)

package app.missiles.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Mobile Ads Unity bridge (com.google.unity.ads, classes2.dex) ───────
//
// Real ad stack: AdMob driven from native IL2CPP C# (GoogleMobileAds plugin)
// through these official `com.google.unity.ads.*` Java bridge classes. The
// C# layer waits on the Java callback interfaces (onAdLoaded / onUserEarnedReward
// / full-screen callbacks) to drive game logic — that is what we spoof.
//
// Every class/method name below is Google's official public API — NOT obfuscated,
// stable across app updates. Previously patched IronSource ISDemandOnly* methods
// had ZERO callers (dead code) and were removed; see notes/ad-removal-v2.md.

/**
 * Interstitial.loadAd(String, AdRequest)V (classes2, smali line 162, .registers 5).
 * Real body runs a Runnable on the UI thread that loads a real GMA interstitial.
 * We replace the body: fire onInterstitialAdLoaded() so C# thinks an ad is ready.
 */
object InterstitialLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * Interstitial.show()V (classes2, smali line 187, .registers 3).
 * Guarded by `interstitialAd == null`; real body runs Interstitial$3 on the UI
 * thread → real GMA AdMob crash-screen. We replace the body with full-screen
 * callbacks so C# closes the ad flow without showing anything.
 * No filters: Morphe string() is EXACT-match, and the smali const-string is the
 * full sentence (smali line 198) — a truncated prefix would never match.
 * name=show with empty params and V return is unique within Interstitial.
 */
object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityRewardedAd.loadAd(String, AdRequest)V (classes2, smali line 255, .registers 5).
 * Replace with onRewardedAdLoaded() so C# sees the rewarded ad as ready.
 */
object UnityRewardedAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityRewardedAd.show()V (classes2, smali line 318, .registers 3).
 * Replace with onAdShowedFullScreenContent + onUserEarnedReward("reward", 1.0f)
 * + onAdDismissedFullScreenContent → C# grants the reward instantly.
 * No filters: Morphe string() is EXACT-match, and the smali const-string is the
 * full sentence (smali line 329) — a truncated prefix would never match.
 * name=show with empty params and V return is unique within UnityRewardedAd.
 */
object UnityRewardedAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityRewardedAd.getRewardItem()Lcom/google/android/gms/ads/rewarded/RewardItem;
 * (classes2, smali line 162, .registers 6).
 * Real impl FutureTask-blocking on the (null after our patch) rewardedAd field —
 * would NPE/return null. Instead construct a fresh
 * com.google.ads.mediation.unity.UnityReward (public, no-arg ctor, amount=1,
 * type="") — no dependency on the R8-fragile RewardItem.DEFAULT_REWARD field.
 */
object UnityRewardedAdGetRewardItemFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "getRewardItem",
    returnType = "Lcom/google/android/gms/ads/rewarded/RewardItem;",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityRewardedInterstitialAd.loadAd(String, AdRequest)V (classes2, smali line 263).
 * Same shape as UnityRewardedAd.loadAd → onRewardedInterstitialAdLoaded().
 */
object UnityRewardedInterstitialAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityRewardedInterstitialAd.show()V (classes2, smali line 326, .registers 3).
 * Replace with full-screen callbacks + onUserEarnedReward("reward", 1.0f).
 * No filters: Morphe string() is EXACT-match, and the smali const-string is the
 * full sentence (smali line 337) — a truncated prefix would never match.
 * name=show with empty params and V return is unique within UnityRewardedInterstitialAd.
 */
object UnityRewardedInterstitialAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityRewardedInterstitialAd.getRewardItem()Lcom/google/android/gms/ads/rewarded/RewardItem;
 * (classes2, smali line 170, .registers 6). Same as UnityRewardedAd variant:
 * construct a fresh com.google.ads.mediation.unity.UnityReward.
 */
object UnityRewardedInterstitialAdGetRewardItemFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "getRewardItem",
    returnType = "Lcom/google/android/gms/ads/rewarded/RewardItem;",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * UnityAppOpenAd.show()V (classes2, smali line 249, .registers 3).
 * App-open ads fire at app foreground; no-op is the safest single hook
 * (leaves loadAd unpatched so nothing else observes a bogus loaded ad).
 */
object UnityAppOpenAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * Banner.createAdView(String, AdSize)V (classes2, smali line 282, .registers 5).
 * No-op → `adView` stays null and Banner.show()/loadAd() no-op safely
 * (both are guarded by the null check internally).
 */
object BannerCreateAdViewFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner;",
    name = "createAdView",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdSize;"),
    accessFlags = listOf(AccessFlags.PROTECTED)
)
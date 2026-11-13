package app.trafficrider.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Mobile Ads Unity bridge (com.google.unity.ads, classes3.dex) ───────
//
// Traffic Rider uses the official Google Mobile Ads Unity plugin via mediation
// (AppLovin, Liftoff/Vungle, UnityAds mediations all routed through AdMob).
// C# waits on Java callbacks — we spoof them.

object InterstitialLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Interstitial;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityRewardedAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityRewardedAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityRewardedAdGetRewardItemFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedAd;",
    name = "getRewardItem",
    returnType = "Lcom/google/android/gms/ads/rewarded/RewardItem;",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityRewardedInterstitialAdLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityRewardedInterstitialAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityRewardedInterstitialAdGetRewardItemFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityRewardedInterstitialAd;",
    name = "getRewardItem",
    returnType = "Lcom/google/android/gms/ads/rewarded/RewardItem;",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object UnityAppOpenAdShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/UnityAppOpenAd;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object BannerCreateAdViewFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner;",
    name = "createAdView",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/gms/ads/AdSize;"),
    accessFlags = listOf(AccessFlags.PROTECTED)
)

object BannerLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner;",
    name = "loadAd",
    returnType = "V",
    parameters = listOf("Lcom/google/android/gms/ads/AdRequest;"),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object BannerShowFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner;",
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object Banner6RunFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner\$6;",
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object Banner7RunFingerprint : Fingerprint(
    definingClass = "Lcom/google/unity/ads/Banner\$7;",
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

// ── Google Mobile Ads Unity bridge, "nextgen" (com.google.unity.ads.nextgen) ──
//
// 2.20 ships *both* plugin generations. The nextgen bridge talks to the new
// "Ads Mobile SDK" (com.google.android.libraries.ads.mobile.sdk.*) and is the
// path the game actually uses at runtime — logcat shows real mediation fills
// (Liftoff/Vungle) even with every legacy loadAd hooked to a no-op, which is
// only possible if the legacy bridge is dead code.
//
// nextgen ads extend UnityAdBase, so `callback` is declared there as Object and
// must be check-cast to the concrete callback interface.

private const val NEXTGEN_INTERSTITIAL = "Lcom/google/unity/ads/nextgen/UnityInterstitialAd;"
private const val NEXTGEN_REWARDED = "Lcom/google/unity/ads/nextgen/UnityRewardedAd;"
private const val NEXTGEN_REWARDED_INTERSTITIAL = "Lcom/google/unity/ads/nextgen/UnityRewardedInterstitialAd;"
private const val NEXTGEN_APP_OPEN = "Lcom/google/unity/ads/nextgen/UnityAppOpenAd;"
private const val NEXTGEN_BANNER = "Lcom/google/unity/ads/nextgen/UnityBannerAd;"

private const val NEXTGEN_AD_REQUEST = "Lcom/google/android/libraries/ads/mobile/sdk/common/AdRequest;"
private const val NEXTGEN_BANNER_REQUEST = "Lcom/google/android/libraries/ads/mobile/sdk/banner/BannerAdRequest;"
private const val NEXTGEN_REWARD_ITEM = "Lcom/google/android/libraries/ads/mobile/sdk/rewarded/RewardItem;"

object NextGenInterstitialLoadFingerprint : Fingerprint(
    definingClass = NEXTGEN_INTERSTITIAL,
    name = "load",
    returnType = "V",
    parameters = listOf(NEXTGEN_AD_REQUEST),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenInterstitialShowFingerprint : Fingerprint(
    definingClass = NEXTGEN_INTERSTITIAL,
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenRewardedLoadFingerprint : Fingerprint(
    definingClass = NEXTGEN_REWARDED,
    name = "load",
    returnType = "V",
    parameters = listOf(NEXTGEN_AD_REQUEST),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenRewardedShowFingerprint : Fingerprint(
    definingClass = NEXTGEN_REWARDED,
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenRewardedGetRewardItemFingerprint : Fingerprint(
    definingClass = NEXTGEN_REWARDED,
    name = "getRewardItem",
    returnType = NEXTGEN_REWARD_ITEM,
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenRewardedInterstitialLoadFingerprint : Fingerprint(
    definingClass = NEXTGEN_REWARDED_INTERSTITIAL,
    name = "load",
    returnType = "V",
    parameters = listOf(NEXTGEN_AD_REQUEST),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenRewardedInterstitialShowFingerprint : Fingerprint(
    definingClass = NEXTGEN_REWARDED_INTERSTITIAL,
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenRewardedInterstitialGetRewardItemFingerprint : Fingerprint(
    definingClass = NEXTGEN_REWARDED_INTERSTITIAL,
    name = "getRewardItem",
    returnType = NEXTGEN_REWARD_ITEM,
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenAppOpenLoadFingerprint : Fingerprint(
    definingClass = NEXTGEN_APP_OPEN,
    name = "load",
    returnType = "V",
    parameters = listOf(NEXTGEN_AD_REQUEST),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenAppOpenShowFingerprint : Fingerprint(
    definingClass = NEXTGEN_APP_OPEN,
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

object NextGenBannerLoadFingerprint : Fingerprint(
    definingClass = NEXTGEN_BANNER,
    name = "load",
    returnType = "V",
    parameters = listOf(NEXTGEN_BANNER_REQUEST),
    accessFlags = listOf(AccessFlags.PROTECTED)
)

object NextGenBannerShowFingerprint : Fingerprint(
    definingClass = NEXTGEN_BANNER,
    name = "show",
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PROTECTED)
)

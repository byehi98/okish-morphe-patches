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

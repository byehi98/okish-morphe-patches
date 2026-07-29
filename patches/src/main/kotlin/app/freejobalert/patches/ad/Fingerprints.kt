package app.freejobalert.patches.ad

import app.morphe.patcher.Fingerprint

// ── Flutter Google Mobile Ads plugin ──────────────────────────────────────────
// All ad types are loaded via a load() method on each Flutter ad wrapper class.
// These classes are NOT obfuscated (they come from the flutter plugin).

/** Banner ad load — io.flutter.plugins.googlemobileads.FlutterBannerAd */
object FlutterBannerAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterBannerAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** Interstitial ad load — io.flutter.plugins.googlemobileads.FlutterInterstitialAd */
object FlutterInterstitialAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterInterstitialAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** Rewarded ad load — io.flutter.plugins.googlemobileads.FlutterRewardedAd */
object FlutterRewardedAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterRewardedAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** Rewarded interstitial ad load */
object FlutterRewardedInterstitialAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterRewardedInterstitialAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** App Open ad load */
object FlutterAppOpenAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterAppOpenAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** Native ad load */
object FlutterNativeAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterNativeAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** Ad Manager banner ad load */
object FlutterAdManagerBannerAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterAdManagerBannerAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

/** Ad Manager interstitial ad load */
object FlutterAdManagerInterstitialAdLoadFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/FlutterAdManagerInterstitialAd;",
    name = "load",
    returnType = "V",
    parameters = listOf()
)

// ── Pairip license check ──────────────────────────────────────────────────────

/**
 * Local installer check — returns true if installed from Play Store.
 * We return false to skip it (false = bypass, the check is then ignored upstream).
 */
object PairiplocalInstallerCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "performLocalInstallerCheck",
    returnType = "Z",
    parameters = listOf()
)

/**
 * processResponse — called with the server license response code.
 * responseCode 0 = LICENSED. We force p1=0 so it always takes the success path.
 */
object PairipProcessResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "processResponse",
    returnType = "V",
    parameters = listOf("I", "Landroid/os/Bundle;")
)

/**
 * initializeLicenseCheck — public entry point called by the app.
 * Return-void to skip the entire check pipeline.
 */
object PairipInitializeLicenseCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    parameters = listOf()
)
object AbstractAdViewAdapterRequestBannerFingerprint : Fingerprint(
    definingClass = "Lcom/google/ads/mediation/AbstractAdViewAdapter;",
    name = "requestBannerAd",
    returnType = "V"
)

object AbstractAdViewAdapterRequestInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/google/ads/mediation/AbstractAdViewAdapter;",
    name = "requestInterstitialAd",
    returnType = "V"
)

object AbstractAdViewAdapterShowInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/google/ads/mediation/AbstractAdViewAdapter;",
    name = "showInterstitial",
    returnType = "V"
)

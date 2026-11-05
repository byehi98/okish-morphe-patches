package app.offtheroad.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Off The Road 1.18.2 — DEX-only ad bridge fingerprints ─────────────────
//
// The app ships two ad bridges in `classes.dex`:
//   com/utils/Admob.smali      — Google Mobile Ads wrapper
//   com/utils/UnityAds.smali   — Unity Ads wrapper
//
// Native `cApplication` calls them over JNI (cAdmob / cUnityAds singletons at
// 0x917128 / 0x917130). The classes are not obfuscated, so the literals below are
// the shipped names; verified against analysis/offtheroad/smali/classes/com/utils/.

/** `Admob.ShowInterstitial()Z` — smali 1413. Returns whether an ad was shown. */
object AdmobShowInterstitialFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/Admob;",
    name = "ShowInterstitial",
)

/** `Admob.InitBanner()V` — smali 930. Creates/attaches the banner view. */
object AdmobInitBannerFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/Admob;",
    name = "InitBanner",
)

/** `Admob.SetBannerVisible(Z)V` — smali 1338. */
object AdmobSetBannerVisibleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/Admob;",
    name = "SetBannerVisible",
)

/** `Admob.CacheInterstitial()V` — smali 534. Preloads an interstitial. */
object AdmobCacheInterstitialFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/Admob;",
    name = "CacheInterstitial",
)

/** `UnityAds.showInterstitial()Z` — smali 527. */
object UnityAdsShowInterstitialFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/UnityAds;",
    name = "showInterstitial",
)

/** `UnityAds.loadInterstitial()V` — smali 362. Preloads an interstitial. */
object UnityAdsLoadInterstitialFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/UnityAds;",
    name = "loadInterstitial",
)

/** `Admob.HasCachedRewarded()Z` — smali 771. Gates the "watch ad" buttons. */
object AdmobHasCachedRewardedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/Admob;",
    name = "HasCachedRewarded",
)

/** `UnityAds.hasCachedRewardedAd()Z` — smali 235. Gates the "watch ad" buttons. */
object UnityAdsHasCachedRewardedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/UnityAds;",
    name = "hasCachedRewardedAd",
)

/** `Admob.ShowRewarded()V` — smali 1468. */
object AdmobShowRewardedFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/Admob;",
    name = "ShowRewarded",
)

/** `UnityAds.showRewardedAd()V` — smali 589. */
object UnityAdsShowRewardedFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/UnityAds;",
    name = "showRewardedAd",
)

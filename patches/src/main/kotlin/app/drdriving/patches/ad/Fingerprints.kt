package app.drdriving.patches.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * DrDrivingActivity.initAds()V — ad system master switch (called from
 * onCreate). Stubbing this prevents ANY ad (banner/interstitial/rewarded)
 * from ever being initialized. Confirmed smali: DrDrivingActivity.smali:5364.
 */
object InitAdsFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "initAds",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        literal(0xea60), // 60000 ms (lastInterstitialTime init)
        methodCall(definingClass = "Ljava/lang/Thread;", name = "start"),
        methodCall(definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;", name = "createAdMobBannerView")
    )
)

/**
 * DrDrivingActivity.showInterstitial(Z)V — central interstitial display
 * gate. All interstitial call sites funnel here. Confirmed smali:
 * DrDrivingActivity.smali:10620. Package-private (no access flag in smali).
 */
object ShowInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "showInterstitial",
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->interstitial:Lcom/google/android/gms/ads/interstitial/InterstitialAd;"),
        methodCall(definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;", name = "requestNewInterstitial"),
        literal(0x15f90), // 90000 ms
        literal(0x1d4c0), // 120000 ms
        methodCall(definingClass = "Lcom/google/android/gms/ads/interstitial/InterstitialAd;", name = "show")
    )
)

/**
 * DrDrivingActivity.requestNewInterstitial()V — interstitial load.
 * Confirmed smali: DrDrivingActivity.smali:9296.
 */
object RequestNewInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "requestNewInterstitial",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        literal(0x7f0c0063), // R.string.ad_inte_new
        methodCall(definingClass = "Lcom/google/android/gms/ads/interstitial/InterstitialAd;", name = "load")
    )
)

/**
 * DrDrivingActivity.requestNewReward()V — rewarded ad load.
 * Confirmed smali: DrDrivingActivity.smali:1680.
 */
object RequestNewRewardFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "requestNewReward",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(),
    filters = listOf(
        literal(0x7f0c0064), // R.string.ad_reward
        methodCall(definingClass = "Lcom/google/android/gms/ads/rewarded/RewardedAd;", name = "load")
    )
)

/**
 * DrDrivingActivity.createAdMobBannerView()V — banner view creation.
 * Confirmed smali: DrDrivingActivity.smali:4696. Package-private.
 */
object CreateAdMobBannerViewFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "createAdMobBannerView",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lcom/google/android/gms/ads/AdView;", name = "<init>"),
        literal(0x7f0c0065), // R.string.ad_unit_id
        methodCall(definingClass = "Lcom/google/android/gms/ads/BaseAdView;", name = "setAdUnitId")
    )
)

/**
 * DrDrivingActivity.ShowAd()V — banner visibility on. Confirmed smali:
 * DrDrivingActivity.smali:4392.
 */
object ShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/ansangha/drdriving/DrDrivingActivity;",
    name = "ShowAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(smali = "Lcom/ansangha/drdriving/DrDrivingActivity;->gadView:Lcom/google/android/gms/ads/AdView;"),
        methodCall(definingClass = "Lcom/google/android/gms/ads/BaseAdView;", name = "resume")
    )
)

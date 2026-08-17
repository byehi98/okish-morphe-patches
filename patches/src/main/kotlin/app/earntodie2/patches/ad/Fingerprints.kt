package app.earntodie2.patches.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * MAXHelper.showRewardedVideo(String)V — AppLovin MAX rewarded ad entry point.
 * Called by native C++ via JNI when the player taps a rewarded video (cash/gas).
 * Normally displays the ad; on completion the listener fires the native callbacks
 * on_rewarded_video_ad_opened() → on_rewarded_video_ad_rewarded() → closed().
 *
 * Confirmed smali (classes4/com/notdoppler/advertising/MAXHelper.smali:460):
 *   .method public static showRewardedVideo(Ljava/lang/String;)V
 *   .registers 2
 *   sget-object v0, ...->rewardedAd:Lcom/applovin/mediation/ads/MaxRewardedAd;
 *   ...isReady() → showAd(...)
 */
object MaxRewardedVideoShowFingerprint : Fingerprint(
    definingClass = "Lcom/notdoppler/advertising/MAXHelper;",
    name = "showRewardedVideo",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        fieldAccess(smali = "Lcom/notdoppler/advertising/MAXHelper;->rewardedAd:Lcom/applovin/mediation/ads/MaxRewardedAd;"),
        methodCall(
            definingClass = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            name = "isReady"
        )
    )
)

/**
 * MAXHelper.showInterstitial(String)Z — AppLovin MAX interstitial entry point.
 * Called by native C++ when an interstitial should appear. Returns true when shown.
 *
 * Confirmed smali (classes4/.../MAXHelper.smali:406):
 *   .method public static showInterstitial(Ljava/lang/String;)Z
 *   .registers 3
 *   sget-object v0, ...->interstitialAd:Lcom/applovin/mediation/ads/MaxInterstitialAd;
 *   ...isReady() → showAd(...) → const/4 p0, 0x1; return p0
 */
object MaxInterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcom/notdoppler/advertising/MAXHelper;",
    name = "showInterstitial",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        fieldAccess(smali = "Lcom/notdoppler/advertising/MAXHelper;->interstitialAd:Lcom/applovin/mediation/ads/MaxInterstitialAd;"),
        methodCall(
            definingClass = "Lcom/applovin/mediation/ads/MaxInterstitialAd;",
            name = "isReady"
        )
    )
)

/**
 * IronSourceHelper.showRewardedVideo(String)V — IronSource rewarded entry point
 * (secondary ad network; same callback contract as MAXHelper).
 *
 * Confirmed smali (classes5/com/notdoppler/advertising/IronSourceHelper.smali:119):
 *   .method public static showRewardedVideo(Ljava/lang/String;)V
 *   .registers 2
 *   ...isEmpty() → IronSource.showRewardedVideo(...)
 */
object IronSourceRewardedVideoShowFingerprint : Fingerprint(
    definingClass = "Lcom/notdoppler/advertising/IronSourceHelper;",
    name = "showRewardedVideo",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/String;", name = "isEmpty"),
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showRewardedVideo"
        )
    )
)

/**
 * IronSourceHelper.showInterstitial(String)Z — IronSource interstitial entry point.
 *
 * Confirmed smali (classes5/.../IronSourceHelper.smali:79):
 *   .method public static showInterstitial(Ljava/lang/String;)Z
 *   .registers 2
 *   ...IronSource.isInterstitialReady() → showInterstitial(...)
 */
object IronSourceInterstitialShowFingerprint : Fingerprint(
    definingClass = "Lcom/notdoppler/advertising/IronSourceHelper;",
    name = "showInterstitial",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "isInterstitialReady"
        ),
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showInterstitial"
        )
    )
)

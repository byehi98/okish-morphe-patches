package app.bighunter.patches.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Ad-network bridge chokepoints for Big Hunter 3.1.1 (classes2.dex, non-obfuscated).
 * The game mediates AdMob / AppLovin / Unity Ads through three same-shaped bridges in
 * kakarodJavaLibs.data; which one is active is chosen by native/remote config, so all
 * three are fingerprinted. Class+name+returnType+flags anchors are unique per bridge.
 *
 * Each bridge exposes:
 *   public static show()V           — interstitial (.registers 2)
 *   public static showWithReward()V — rewarded (.registers 2)
 *   public static native rewardCallback()V / stopProcess()V — cocos JNI hooks
 */

object AdmobShowFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJAdsAdmob;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

object AdmobShowWithRewardFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJAdsAdmob;",
    name = "showWithReward",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

object AppLovinShowFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJAdsAppLovin;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

object AppLovinShowWithRewardFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJAdsAppLovin;",
    name = "showWithReward",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

object UnityAdsShowFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJAdsUnity;",
    name = "show",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

object UnityAdsShowWithRewardFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJAdsUnity;",
    name = "showWithReward",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

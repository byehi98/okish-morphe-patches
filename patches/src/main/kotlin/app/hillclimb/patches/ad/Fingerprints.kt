package app.hillclimb.patches.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * MainActivity.playRewardedVideoAd(String, int)V — public static, called by
 * the native C++ engine (libgame.so) via JNI whenever a rewarded video reward
 * is due (coins, gems, extra spins, free chests).
 *
 * Confirmed smali: MainActivity.smali:2640 (classes3.dex). It fetches the ads
 * instance (twice — null-guarded), then calls CFirebaseAds.showVideoAd(String)
 * to play the rewarded video. On exception it queues a runnable that fires
 * onVideoStartedFail().
 *
 * showVideoAd(String) is invoked from exactly ONE method in the whole app
 * (MainActivity.smali:2651), so the filter is unambiguous. Filters land in
 * exact instruction order (0-based indices inside the method):
 *   0. MainActivity.getAdsInstance()      (index 0  — first call)
 *   1. CFirebaseAds.showVideoAd(String)   (index 5  — the actual ad playback)
 */
object PlayRewardedVideoAdFingerprint : Fingerprint(
    definingClass = "Lcom/fingersoft/game/MainActivity;",
    name = "playRewardedVideoAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "I"),
    filters = listOf(
        methodCall(definingClass = "Lcom/fingersoft/game/MainActivity;", name = "getAdsInstance"),
        methodCall(definingClass = "Lcom/fingersoft/game/firebase/CFirebaseAds;", name = "showVideoAd")
    )
)

package app.moviebox.patches.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.transsion.member.MemberProvider.g()Z — IMemberApi skip-ad flag.
 *
 * Returns MMKV boolean kv_is_skip_ad (default false). The server normally
 * sets it via checkShowAdState(); forcing it true marks the user ad-free.
 * Class is stable; method name ("g") is obfuscated but pinned to this
 * version, and the kv key string makes the match unambiguous.
 */
object MemberProviderSkipAdFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/MemberProvider;",
    name = "g",
    returnType = "Z",
    filters = listOf(
        string("kv_is_skip_ad")
    )
)

/**
 * hi/e.a()Z — central ad-free state read by the ad framework
 * (com/transsion/ad/MBAd, com/transsion/ad/scene/c).
 *
 * Reads MMKV key "j376W52LrKvau6r8" (default false). Class name is
 * obfuscated but the MMKV key string is a stable anchor.
 */
object SkipShowAdStateFingerprint : Fingerprint(
    definingClass = "Lhi/e;",
    name = "a",
    returnType = "Z",
    filters = listOf(
        string("j376W52LrKvau6r8")
    )
)

/**
 * SplashActivity.startSplashAdLoad()V (method name "d0" in bytecode).
 *
 * Launches the SplashActivity$startSplashAdLoad$1 coroutine that loads and
 * shows the bidding splash ad. Fingerprinted on the coroutine constructor —
 * the coroutine class name comes from Kotlin metadata and is stable.
 */
object SplashStartAdLoadFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/subroom/activity/SplashActivity;",
    returnType = "V",
    filters = listOf(
        methodCall(
            smali = "Lcom/transsion/subroom/activity/SplashActivity\$startSplashAdLoad\$1;-><init>(Lcom/transsion/subroom/activity/SplashActivity;Lkotlin/coroutines/Continuation;)V"
        )
    )
)

/**
 * com.hisavana.mintegral.executer.MintegralVideo.initVideo()V — reward video
 * listener setup. No-op'ing it leaves the listener null so onVideoStartLoad()
 * bails before the SDK loads anything.
 */
object MintegralVideoInitFingerprint : Fingerprint(
    definingClass = "Lcom/hisavana/mintegral/executer/MintegralVideo;",
    name = "initVideo",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED)
)

/**
 * com.hisavana.mintegral.executer.MintegralBanner.showBanner()V — displays
 * the MBBannerView. No-op prevents banner display.
 */
object MintegralBannerShowFingerprint : Fingerprint(
    definingClass = "Lcom/hisavana/mintegral/executer/MintegralBanner;",
    name = "showBanner",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED)
)

/**
 * com.hisavana.mintegral.executer.MintegralNative.initNative()V — native ad
 * setup/load entry.
 */
object MintegralNativeInitFingerprint : Fingerprint(
    definingClass = "Lcom/hisavana/mintegral/executer/MintegralNative;",
    name = "initNative",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED)
)

/**
 * com.hisavana.mintegral.executer.MintegralInterstitial.initInterstitial()V —
 * interstitial setup/load entry.
 */
object MintegralInterstitialInitFingerprint : Fingerprint(
    definingClass = "Lcom/hisavana/mintegral/executer/MintegralInterstitial;",
    name = "initInterstitial",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED)
)

/**
 * com.hisavana.mintegral.executer.MintegralSplash.onSplashStartLoad()V —
 * splash load entry (backup for the SplashActivity patch).
 */
object MintegralSplashStartLoadFingerprint : Fingerprint(
    definingClass = "Lcom/hisavana/mintegral/executer/MintegralSplash;",
    name = "onSplashStartLoad",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED)
)

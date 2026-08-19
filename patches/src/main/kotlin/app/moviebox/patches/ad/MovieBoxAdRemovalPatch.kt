package app.moviebox.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.moviebox.patches.shared.Constants.COMPATIBILITY_MOVIEBOX

private val returnTrue = """
    const/4 v0, 0x1
    return v0
""".trimIndent()

private val returnVoid = """
    return-void
""".trimIndent()

@Suppress("unused")
val movieBoxAdRemovalPatch = bytecodePatch(
    name = "MovieBox Ad Removal",
    description = "Removes splash, banner, native, interstitial and reward video ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MOVIEBOX)

    execute {
        // Ad-free flags — the ad framework checks these before showing ads.
        MemberProviderSkipAdFingerprint.method.addInstructions(0, returnTrue)
        SkipShowAdStateFingerprint.method.addInstructions(0, returnTrue)

        // Splash: bypass the ad load coroutine and go straight to MainActivity
        // (toMain(false) is the normal no-ad fallback path).
        SplashStartAdLoadFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            invoke-direct {p0, v0}, Lcom/transsion/subroom/activity/SplashActivity;->e0(Z)V
            return-void
        """.trimIndent())

        // Mintegral executors: kill load/display entry points.
        MintegralVideoInitFingerprint.method.addInstructions(0, returnVoid)
        MintegralBannerShowFingerprint.method.addInstructions(0, returnVoid)
        MintegralNativeInitFingerprint.method.addInstructions(0, returnVoid)
        MintegralInterstitialInitFingerprint.method.addInstructions(0, returnVoid)
        MintegralSplashStartLoadFingerprint.method.addInstructions(0, returnVoid)
    }
}

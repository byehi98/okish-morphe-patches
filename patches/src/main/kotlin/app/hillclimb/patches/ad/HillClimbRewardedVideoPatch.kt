package app.hillclimb.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.hillclimb.patches.shared.Constants.COMPATIBILITY_HILLCLIMB

// Smali class descriptors. The \$ escapes keep Kotlin string interpolation
// from treating "$$ExternalSyntheticLambda6" as a template expression.
private const val MAIN_ACTIVITY = "Lcom/fingersoft/game/MainActivity;"
private const val LAMBDA_VIDEO_STARTED = "Lcom/fingersoft/game/MainActivity\$\$ExternalSyntheticLambda6;"
private const val LAMBDA_VIDEO_CLOSED = "Lcom/fingersoft/game/MainActivity\$\$ExternalSyntheticLambda11;"

/**
 * Hill Climb Racing — Instant Rewarded Video Rewards
 *
 * playRewardedVideoAd(String, int) is the JNI entry the native C++ engine
 * (libgame.so) calls to grant a rewarded-video reward. Normally it plays the
 * ad via CFirebaseAds.showVideoAd(String) and waits for the ad SDK's
 * onVideoStarted/onVideoClosed callbacks. We skip the ad entirely and instead
 * fire the SAME native callbacks the engine uses for a completed video:
 *
 *   MainActivity$$ExternalSyntheticLambda6  (Runnable) → lambda$onVideoStarted$15
 *     → MainActivity.onVideoStartedSuccess()   (native — tells engine "ad started")
 *   MainActivity$$ExternalSyntheticLambda11 (Runnable) → lambda$onVideoClosed$16
 *     → MainActivity.onVideoCompletedSuccess() (native — tells engine "video done, grant reward")
 *
 * Both lambdas are queued via MainActivity.queueOnGLThread(Runnable), the
 * exact same pattern the game's own onVideoStarted(Bundle)/onVideoClosed(Bundle)
 * handlers use (MainActivity.smali:5136 / 5068). The native reward-granting
 * functions require the GL thread, so we never call them directly.
 *
 * Register safety: playRewardedVideoAd has .registers 2 (v0=p0 String adUnit,
 * v1=p1 int). We insert at index 0 and immediately clobber v0 with
 * new-instance — the original adUnit param is dead after our early return.
 * The original body (including the exception handler) stays in place but is
 * unreachable. This mirrors the game's own exception path, which also
 * clobbers p0 with new-instance (MainActivity.smali:2656).
 *
 * Confirmed smali: MainActivity.smali:2640, 2354 (lambda$onVideoStarted$15),
 * 2340 (lambda$onVideoClosed$16), ExternalSyntheticLambda6/11 run() methods.
 */
@Suppress("unused")
val hillClimbRewardedVideoPatch = bytecodePatch(
    name = "Hill Climb Racing Instant Rewarded Video Rewards",
    description = "Rewarded video ads grant their reward instantly without playing the ad: the native engine receives onVideoStartedSuccess + onVideoCompletedSuccess on the GL thread, exactly as if the video had been watched and completed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_HILLCLIMB)

    execute {
        PlayRewardedVideoAdFingerprint.method.addInstructions(0, """
            new-instance v0, $LAMBDA_VIDEO_STARTED
            invoke-direct {v0}, $LAMBDA_VIDEO_STARTED-><init>()V
            invoke-static {v0}, $MAIN_ACTIVITY->queueOnGLThread(Ljava/lang/Runnable;)V
            new-instance v0, $LAMBDA_VIDEO_CLOSED
            invoke-direct {v0}, $LAMBDA_VIDEO_CLOSED-><init>()V
            invoke-static {v0}, $MAIN_ACTIVITY->queueOnGLThread(Ljava/lang/Runnable;)V
            return-void
        """.trimIndent())
    }
}

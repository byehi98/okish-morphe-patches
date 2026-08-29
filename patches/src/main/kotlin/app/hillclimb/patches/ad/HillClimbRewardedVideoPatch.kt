package app.hillclimb.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.hillclimb.patches.shared.Constants.COMPATIBILITY_HILLCLIMB

// Smali class descriptors.
private const val MAIN_ACTIVITY = "Lcom/fingersoft/game/MainActivity;"

/**
 * Hill Climb Racing — Instant Rewarded Video Rewards
 *
 * playRewardedVideoAd(String, int) is the JNI entry the native C++ engine
 * (libgame.so) calls to grant a rewarded-video reward. Normally it plays the
 * ad via CFirebaseAds.showVideoAd(String) and waits for the ad SDK's
 * onVideoStarted/onVideoClosed callbacks. We skip the ad entirely and instead
 * fire the SAME native callbacks the engine uses for a completed video:
 *
 *   MainActivity.onVideoStartedSuccess()    (native — tells engine "ad started")
 *   MainActivity.onVideoCompletedSuccess()  (native — tells engine "video done, grant reward")
 *
 * We call these native JNI methods DIRECTLY rather than queuing the app's
 * synthetic "ExternalSyntheticLambdaN" Runnables. Those lambda class names are
 * assigned by the compiler and SHIFT between builds: in 1.70.0 they were
 * Lambda6/Lambda11 (→ onVideoStartedSuccess / onVideoCompletedSuccess), but in
 * 1.71.1 the same classes map to unrelated code (initAdsIfParametersAreComplete
 * / onSettingsRefreshed). The old patch therefore never delivered the reward
 * callbacks, so the engine waited forever for a reward that never arrived and
 * the game hung on the bonus screen. The native success methods are a stable
 * JNI interface (present in 1.70.0 and 1.71.1), so calling them directly is
 * version-independent. playRewardedVideoAd is itself the engine's JNI entry and
 * already runs on the GL/game thread, so no extra queueing is required.
 *
 * Register safety: playRewardedVideoAd has .registers 2 (p0=String adUnit,
 * p1=int). Our injected block uses invoke-static {} (no registers) and returns,
 * so it is safe regardless of register allocation. The original body (including
 * the exception handler) stays in place but is unreachable.
 */
@Suppress("unused")
val hillClimbRewardedVideoPatch = bytecodePatch(
    name = "Hill Climb Racing Instant Rewarded Video Rewards",
    description = "Rewarded video ads grant their reward instantly without playing the ad: the native engine receives onVideoStartedSuccess + onVideoCompletedSuccess on the GL thread, exactly as if the video had been watched and completed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_HILLCLIMB)

    execute {
        // Fire the exact same native JNI callbacks the ad SDK's onVideoStarted /
        // onVideoClosed handlers deliver to the engine. We call them directly
        // instead of queuing the app's synthetic "ExternalSyntheticLambdaN"
        // Runnables, because those lambda class names are assigned by the
        // compiler and SHIFT between builds: in 1.70.0 they were Lambda6/Lambda11,
        // but in 1.71.1 the same classes map to unrelated code
        // (initAdsIfParametersAreComplete$3 / onSettingsRefreshed$2). The old
        // patch therefore never delivered onVideoStartedSuccess /
        // onVideoCompletedSuccess, so the engine waited forever for a reward that
        // never arrived and the game hung on the bonus screen. The native success
        // methods are a stable JNI interface (present in 1.70.0 and 1.71.1), so
        // calling them directly is version-independent. playRewardedVideoAd is
        // itself the engine's JNI entry and already runs on the GL/game thread.
        PlayRewardedVideoAdFingerprint.method.addInstructions(0, """
            invoke-static {}, $MAIN_ACTIVITY->onVideoStartedSuccess()V
            invoke-static {}, $MAIN_ACTIVITY->onVideoCompletedSuccess()V
            return-void
        """.trimIndent())
    }
}

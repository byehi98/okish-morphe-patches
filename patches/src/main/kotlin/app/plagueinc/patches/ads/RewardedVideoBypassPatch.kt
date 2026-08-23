package app.plagueinc.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Skips rewarded video ads while still granting the reward.
 *
 * Real flow (confirmed in VideoAdController listener methods):
 *   ad watched -> onReward.run() (line ~415) -> ad hidden -> onClose.run() (line ~268)
 *
 * This patch rewrites createRewardedAd() to fire the reward callback (p1)
 * followed by the close callback (p3) immediately, then return — exactly
 * mimicking a successful watch without any ad request or network call.
 * Gene insertion and other rewarded actions complete instantly.
 */
@Suppress("unused")
val plagueIncRewardedVideoBypassPatch = bytecodePatch(
    name = "Plague Inc. Skip Rewarded Video Ads",
    description = "Grants rewarded video bonuses instantly without watching ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        // createRewardedAd has .registers 6: v0..v2 locals + p0..p3 params.
        // Inserted code only uses p1/p3, so it is safe at index 0.
        CreateRewardedAdFingerprint.method.addInstructionsWithLabels(0, """
            if-eqz p1, :plague_skip_reward
            invoke-interface {p1}, Ljava/lang/Runnable;->run()V
            :plague_skip_reward
            if-eqz p3, :plague_skip_close
            invoke-interface {p3}, Ljava/lang/Runnable;->run()V
            :plague_skip_close
            return-void
        """)
    }
}

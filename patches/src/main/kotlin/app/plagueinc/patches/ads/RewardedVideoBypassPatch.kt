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
 * This patch rewrites createRewardedAd() to fire the reward callback (p2)
 * followed by the close callback (p4) immediately, then return — exactly
 * mimicking a successful watch without any ad request or network call.
 * Gene insertion and other rewarded actions complete instantly.
 *
 * 1.27.3 signature: createRewardedAd(String placement, Runnable onReward,
 * Runnable onFail, Runnable onClose) — so p1 is the placement string and the
 * callbacks are p2/p3/p4. The old 1.26.1 signature had no placement argument.
 */
@Suppress("unused")
val plagueIncRewardedVideoBypassPatch = bytecodePatch(
    name = "Plague Inc. Skip Rewarded Video Ads",
    description = "Grants rewarded video bonuses instantly without watching ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        // createRewardedAd has .registers 7: v0..v2 locals + p0..p4 params
        // (this + String + 3 Runnables). Inserted code only uses p2/p4.
        CreateRewardedAdFingerprint.method.addInstructionsWithLabels(0, """
            if-eqz p2, :plague_skip_reward
            invoke-interface {p2}, Ljava/lang/Runnable;->run()V
            :plague_skip_reward
            if-eqz p4, :plague_skip_close
            invoke-interface {p4}, Ljava/lang/Runnable;->run()V
            :plague_skip_close
            return-void
        """)
    }
}

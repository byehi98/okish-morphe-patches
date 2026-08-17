package app.earntodie2.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.earntodie2.patches.shared.Constants.COMPATIBILITY_EARNTODIE2

@Suppress("unused")
val earnToDie2AdRemovalPatch = bytecodePatch(
    name = "Earn to Die 2 No Ads + Instant Rewards",
    description = "Removes interstitial and rewarded ads, and grants rewarded-video rewards instantly by firing the native callbacks.",
    default = true
) {
    compatibleWith(COMPATIBILITY_EARNTODIE2)

    execute {
        // --- MAXHelper (AppLovin MAX, primary network) ---

        // Rewarded video: replace showRewardedVideo with the full success
        // callback sequence. C++ receives opened → rewarded → closed, so it
        // grants the reward instantly and never waits for a real ad.
        // .registers 2 — invoke-static {} uses no registers; return-void safe.
        MaxRewardedVideoShowFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/notdoppler/advertising/MAXHelper;->on_rewarded_video_ad_opened()V
            invoke-static {}, Lcom/notdoppler/advertising/MAXHelper;->on_rewarded_video_ad_rewarded()V
            invoke-static {}, Lcom/notdoppler/advertising/MAXHelper;->on_rewarded_video_ad_closed()V
            return-void
        """.trimIndent())

        // Interstitial: fire opened → show_succeeded → closed so the C++
        // ad state machine completes normally (no hang), then return true
        // ("shown"). .registers 3 — v0 is a safe local for the boolean.
        MaxInterstitialShowFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/notdoppler/advertising/MAXHelper;->on_interstitial_ad_opened()V
            invoke-static {}, Lcom/notdoppler/advertising/MAXHelper;->on_interstitial_ad_show_succeeded()V
            invoke-static {}, Lcom/notdoppler/advertising/MAXHelper;->on_interstitial_ad_closed()V
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // --- IronSourceHelper (secondary network, same contract) ---

        IronSourceRewardedVideoShowFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/notdoppler/advertising/IronSourceHelper;->on_rewarded_video_ad_opened()V
            invoke-static {}, Lcom/notdoppler/advertising/IronSourceHelper;->on_rewarded_video_ad_rewarded()V
            invoke-static {}, Lcom/notdoppler/advertising/IronSourceHelper;->on_rewarded_video_ad_closed()V
            return-void
        """.trimIndent())

        IronSourceInterstitialShowFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/notdoppler/advertising/IronSourceHelper;->on_interstitial_ad_opened()V
            invoke-static {}, Lcom/notdoppler/advertising/IronSourceHelper;->on_interstitial_ad_show_succeeded()V
            invoke-static {}, Lcom/notdoppler/advertising/IronSourceHelper;->on_interstitial_ad_closed()V
            const/4 v0, 0x1
            return v0
        """.trimIndent())
    }
}

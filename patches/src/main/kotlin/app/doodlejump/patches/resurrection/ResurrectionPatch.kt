package app.doodlejump.patches.resurrection

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.doodlejump.patches.shared.Constants.COMPATIBILITY_DOODLEJUMP

// Smali class descriptors
private const val AD_TYPE = "Lcom/limasky/doodlejumpandroid/AdType;"
private const val NOTIFICATION_CENTER = "Lcom/limasky/doodlejumpandroid/NotificationCenter;"
private const val MSG_AD_STATUS_DATA = "Lcom/limasky/doodlejumpandroid/Messages\$MsgAdStatusData;"

// Message IDs (confirmed in Messages.smali)
private const val MSG_SHOW_AD = 0x1f        // 31  — engine -> Java: show an ad (TRIGGER)
private const val MSG_AD_WILL_APPEAR = 0x20 // 32  — Java -> engine: ad about to show
private const val MSG_AD_REWARDED = 0x23    // 35  — Java -> engine: REVIVE / reward granted

/**
 * Smali that grants the resurrection immediately for ANY "show ad" (0x1f) request
 * WITHOUT ever contacting/showing any rewarded-ad SDK.
 *
 * Why gate on the message ID only (not the gift adType):
 * the engine requests the revive ad via Msg_Show_Ad (0x1f). At this handler the
 * payload adType has NOT yet been remapped by GetAdStatus, and on many builds the
 * revive request does not carry the VideoGiftInterstitial (0x8) bit at this point,
 * so a gift-bit check would miss it. Instead we intercept every 0x1f and send
 * Msg_Ad_Rewarded (0x23) straight to the engine — the exact signal
 * IronSourceManager.onVideoRewarded uses to revive the player (0x23 alone is
 * sufficient; no 0x20/MsgAdStatusData needed). The ad is never dispatched to any
 * network, so it never shows.
 *
 * Registers: handleMessage(ILjava/lang/Object;I)I is `.locals 5` (v0..v4) with
 * p0=this, p1=msgId, p2=payload. v1/v2/v3 are free locals at index 0; v0 is set
 * to 0 for the return value. On the skip path the original code runs unmodified.
 */
private fun resurrectionMediationSmali() = """
    const/16 v1, $MSG_SHOW_AD
    if-ne p1, v1, :showad_skip
    const/4 v2, 0x0
    const/16 v3, $MSG_AD_REWARDED
    invoke-static {v3, v2, v2, v2}, $NOTIFICATION_CENTER->sendMessage(ILjava/lang/Object;II)I
    const/4 v0, 0x0
    return v0
    :showad_skip
    nop
""".trimIndent()

/**
 * Smali that grants the resurrection for the gift (revive) ad when it reaches a
 * network-specific manager (IronSource) directly. Backup for builds that route
 * the gift ad here instead of (or in addition to) the mediation handler.
 *
 * Registers: loadAd(I)V is `.locals 4` (v0..v3) with p0=this, p1=adType. Only
 * v0/v1/v2 are touched; v3 (used later by the original) is left untouched.
 */
private fun resurrectionSmali() = """
    invoke-static {p1}, $AD_TYPE->HasVideoGiftInterstitial(I)Z
    move-result v0
    if-eqz v0, :resurrect_skip
    const/4 v1, 0x0
    new-instance v0, $MSG_AD_STATUS_DATA
    invoke-direct {v0}, $MSG_AD_STATUS_DATA-><init>()V
    const/16 v2, 0x8
    iput v2, v0, $MSG_AD_STATUS_DATA->adTypeSelected:I
    const/4 v2, 0x1
    iput v2, v0, $MSG_AD_STATUS_DATA->status:I
    const/16 v2, $MSG_AD_WILL_APPEAR
    invoke-static {v2, v0, v1, v1}, $NOTIFICATION_CENTER->sendMessage(ILjava/lang/Object;II)I
    const/16 v2, $MSG_AD_REWARDED
    invoke-static {v2, v1, v1, v1}, $NOTIFICATION_CENTER->sendMessage(ILjava/lang/Object;II)I
    return-void
    :resurrect_skip
    nop
""".trimIndent()

@Suppress("unused")
val doodleJumpResurrectionPatch = bytecodePatch(
    name = "Doodle Jump Resurrection Bypass",
    description = "Resurrect instantly after death without watching a rewarded ad.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DOODLEJUMP)

    execute {
        // Primary (network-agnostic): intercept the engine's "show ad" request at
        // the mediation handler and grant the revive before any SDK is contacted.
        ResurrectionShowAdFingerprint.method.addInstructionsWithLabels(0, resurrectionMediationSmali())

        // Backup (per-network): catch the gift ad if it reaches IronSource's loadAd.
        ResurrectionLoadAdFingerprint.method.addInstructionsWithLabels(0, resurrectionSmali())
    }
}

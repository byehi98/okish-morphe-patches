package app.moviebox.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.moviebox.patches.shared.Constants.COMPATIBILITY_MOVIEBOX

private val returnTrue = """
    const/4 v0, 0x1
    return v0
""".trimIndent()

@Suppress("unused")
val movieBoxPremiumPatch = bytecodePatch(
    name = "MovieBox Premium Unlock",
    description = "Unlocks MovieBox Premium (VIP) by forcing the member state active at Pro level.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MOVIEBOX)

    execute {
        // Bean level: every consumer reads MemberInfo.isActive()/getVipLevel()
        MemberInfoIsActiveFingerprint.method.addInstructions(0, returnTrue)
        MemberInfoVipLevelFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // Consumer level: return true even when the server sent no member
        // record (memberDetail == null) — covers quality dialog, premium
        // content, downloads UI.
        MemberProviderIsMemberFingerprint.method.addInstructions(0, returnTrue)
        PremiumProviderIsActiveFingerprint.method.addInstructions(0, returnTrue)
        PremiumProviderIsProFingerprint.method.addInstructions(0, returnTrue)

        // Member-guide banner ("premium ends in 0 days"): push it into the
        // hidden branch — daysLeft > 7 AND isProMember → setVisibility(GONE).
        PremiumDaysLeftFingerprint.method.addInstructions(0, """
            const/16 v0, 0x1e
            invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v0
            return-object v0
        """.trimIndent())
        PremiumIsProMemberFingerprint.method.addInstructions(0, returnTrue)

        // Member payment entry surface (kv_is_pay_enable_member, default
        // false): mod forces it true — return true so the pay-enable member
        // UI surfaces.
        MemberProviderPayEnableFingerprint.method.addInstructions(0, returnTrue)
    }
}

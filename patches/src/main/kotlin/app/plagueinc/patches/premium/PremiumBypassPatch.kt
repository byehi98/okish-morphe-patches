package app.plagueinc.patches.premium

import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC
import app.morphe.util.addInstructionsToEnd
import app.plagueinc.patches.premium.PurchaseManagerInitFingerprint

/**
 * Forces the app into premium mode.
 *
 * The new version stores premium state in the field `k:Z` of the PurchaseManager
 * singleton (js0) instead of an isPremium() method. Setting it to true in the
 * constructor unlocks every Java-side premium gate.
 */
@Suppress("unused")
val plagueIncPremiumBypassPatch = bytecodePatch(
    name = "Plague Inc. Premium",
    description = "Unlocks all premium features and disables premium prompts.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        PurchaseManagerInitFingerprint.method.addInstructionsToEnd("""
            const/4 v0, 0x1
            iput-boolean v0, p0, Ljs0;->k:Z
        """)
    }
}

package app.freejobalert.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.freejobalert.patches.ad.PairipInitializeLicenseCheckFingerprint
import app.freejobalert.patches.ad.PairipProcessResponseFingerprint
import app.freejobalert.patches.ad.PairiplocalInstallerCheckFingerprint
import app.freejobalert.patches.shared.Constants.COMPATIBILITY_FREEJOBALERT

@Suppress("unused")
val freeJobAlertLicenseBypassPatch = bytecodePatch(
    name = "FreeJobAlert License Bypass",
    description = "Bypasses the Pairip Play Store installation check so the app launches normally.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FREEJOBALERT)

    execute {
        // 1. Skip the entire license check pipeline at the entry point.
        //    initializeLicenseCheck() is the public method the app calls on startup.
        PairipInitializeLicenseCheckFingerprint.method
            .addInstructions(0, "return-void")

        // 2. Make performLocalInstallerCheck() always return false (= not from Play Store,
        //    but this return value is only used to decide whether to skip the remote check —
        //    since we already killed initializeLicenseCheck above, this is defense-in-depth).
        PairiplocalInstallerCheckFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // 3. Force processResponse() to treat every server reply as responseCode=0 (LICENSED).
        //    p1 is the responseCode parameter; setting it to 0 routes into the success branch.
        PairipProcessResponseFingerprint.method
            .addInstructions(0, "const/4 p1, 0x0")
    }
}

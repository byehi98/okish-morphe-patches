package app.onlyone.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.onlyone.patches.shared.Constants.COMPATIBILITY_ONLY_ONE

@Suppress("unused")
val onlyOneLicenseBypassPatch = bytecodePatch(
    name = "Only One License Bypass",
    description = "Bypasses the PairIP Play Store license check so the app launches normally.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ONLY_ONE)

    execute {
        // initializeLicenseCheck()V is the sole entry point of the PairIP license
        // machinery (manifest-registered LicenseContentProvider.onCreate calls it
        // before Application.onCreate). Returning immediately severs every failure
        // path downstream: no licensing-service bind, no CHECK_LICENSE_V2 parcel
        // transact, no JWS response validation, no paywall/error dialog, no
        // repeated re-checks, no System.exit. Nothing else in any DEX references
        // the package (verified: LicenseClient.checkLicense has zero callers).
        //
        // Fingerprint resolution throws a clear PatchException if the method is
        // not found — the patch fails loudly instead of silently passing.
        PairipInitializeLicenseCheckFingerprint.method
            .addInstructions(0, "return-void")
    }
}

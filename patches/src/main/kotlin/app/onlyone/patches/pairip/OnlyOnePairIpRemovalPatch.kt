package app.onlyone.patches.pairip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.onlyone.patches.shared.Constants.COMPATIBILITY_ONLY_ONE

/**
 * Only One — PairIP removal.
 *
 * PairIP is the startup license gate: without neutralization a non-Play
 * install gets a fullscreen "Something went wrong" error dialog (or the
 * NOT_LICENSED paywall) and the app exits via `System.exit(0)`. All PairIP
 * code lives in classes2.dex (`com.pairip.licensecheck.*` +
 * `com.pairip.application.Application`); zero references exist in the other
 * dexes, and this build has no SignatureCheck / IntegrityCheck / VMRuntime
 * components — so neutering the check entries is sufficient, no crypto or
 * native bypass needed.
 *
 * 1.3040 entry chain (verified by baksmali of 1.3040 classes2.dex):
 *
 *   com.pairip.application.Application.attachBaseContext
 *     → LicenseClient.checkLicense(Context)          [1: static entry]
 *       → lambda$checkLicense$0 → initializeLicenseCheck()  [2: fan-out]
 *           → bind CHECK_LICENSE service / transact / processResponse
 *           → handleError → startErrorDialogActivity + LicenseClient$1.exitAction
 *
 * Patch (four trivially-safe entry/exit neuterings, no register subtleties):
 *   1. checkLicense → return-void: kills both live entries
 *      (Application.attachBaseContext AND LicenseContentProvider.onCreate).
 *   2. initializeLicenseCheck → return-void: defense-in-depth; no service
 *      bind, transact, dialogs, repeated checks or exit from any direct call.
 *   3. LicenseContentProvider.onCreate → return true: legacy bootstrap dead
 *      (class unregistered in the 1.3040 manifest but still shipped in dex).
 *   4. LicenseClient$1.run → return-void: System.exit(0) can never fire.
 *
 * Prerequisite for the Free Store / IAP bypass patches on non-Play installs;
 * harmless on Play installs (the license would pass there anyway).
 */
@Suppress("unused")
val onlyOnePairIpRemovalPatch = bytecodePatch(
    name = "Only One PairIP removal",
    description = "Disables the PairIP license check so the game starts without " +
        "Google Play licensing, error dialogs or forced exits.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ONLY_ONE)

    execute {
        // 1. Static entry — both Application.attachBaseContext and the
        //    provider funnel through here.
        LicenseClientCheckLicenseFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 2. Instance fan-out — no bind / transact / dialogs / re-checks.
        LicenseClientInitCheckFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // 3. Legacy provider bootstrap — immediately report success.
        LicenseContentProviderOnCreateFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // 4. Exit action — System.exit(0) never fires.
        LicenseClientExitActionFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())
    }
}

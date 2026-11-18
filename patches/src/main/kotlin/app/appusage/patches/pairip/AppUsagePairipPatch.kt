package app.appusage.patches.pairip

import app.appusage.patches.shared.Constants.COMPATIBILITY_APP_USAGE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * PairIP license-check bypass for App Usage (com.a0soft.gphone.uninstaller 6.19).
 *
 * PairIP here is the old Java-only variant — no libpairipcore.so, no
 * com.pairip.VMRunner, no string-integrity VM. Everything lives in classes2.dex:
 * com/pairip/application/Application plus com/pairip/licensecheck.
 *
 * Enforcement chain (verified in smali):
 *   Application.attachBaseContext()  ─┐
 *   LicenseContentProvider.onCreate() ─┴→ LicenseClient.checkLicense(Context)
 *     → initializeLicenseCheck() → performLocalInstallerCheck()
 *       (installer package must be com.android.vending → false on sideload)
 *     → bind Play licensing service → bind failure or responseCode 2 (NOT_LICENSED)
 *     → startPaywallActivity()/startErrorDialogActivity()
 *     → scheduleAppShutdown() → delayedTaskExecutor.schedule(System.exit(0), 30 s)
 *
 * Both entry points call this one static method, and initializeLicenseCheck() has
 * no other caller, so replacing its body with `return-void` removes the installer
 * check, the Play licensing bind, the dialog and the delayed System.exit in a
 * single edit. LicenseActivity, scheduleAppShutdown, attachBaseContext and
 * LicenseContentProvider are deliberately left untouched.
 *
 * Injection at index 0 is verifier-safe: checkLicense has `.registers 3`, no
 * try/catch region and no monitor-enter anywhere in the method, and `return-void`
 * reads no registers, so no local or parameter has to be re-established.
 */
@Suppress("unused")
val appUsagePairipPatch = bytecodePatch(
    name = "Bypass PairIP license check",
    description = "Disables PairIP (com.pairip.licensecheck) verification: no install-source " +
        "check, no Play licensing bind, no paywall/error dialog and no delayed process exit. " +
        "Required for sideloaded or re-signed APKs, which PairIP would otherwise close after 30 seconds.",
) {
    compatibleWith(COMPATIBILITY_APP_USAGE)

    execute {
        CheckLicenseFingerprint.method.addInstructions(0, "return-void")
    }
}

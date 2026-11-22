package app.appusage.patches.pairip

import app.appusage.patches.shared.Constants.COMPATIBILITY_APP_USAGE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * PairIP license-check bypass for App Usage (com.a0soft.gphone.uninstaller 6.20).
 *
 * PairIP here is still the Java-only variant — the merged APK contains no
 * `libpairipcore.so` and no `com.pairip.VMRunner`, so there is no native license
 * VM and no string-integrity VM. Everything lives in classes2.dex:
 * `com/pairip/application/Application` plus `com/pairip/licensecheck`.
 *
 * 6.20 upgraded the client to the "v2" API (isLocalCheckPassed(),
 * retryOrThrow(), TrialClient, ILicenseV2ResultListener, LicenseResponseHelper,
 * RepeatedCheckMetadata), but the entry surface is unchanged:
 *
 *   Application.attachBaseContext()   ─┐
 *   LicenseContentProvider.onCreate() ─┴→ LicenseClient.checkLicense(Context)
 *     → mainThreadRunner.run(lambda11) → initializeLicenseCheck()
 *       → isLocalCheckPassed() / performLocalInstallerCheck()
 *         (installer package must be Play → false on sideload)
 *     → bind Play licensing service → bind failure, NOT_LICENSED or a check
 *       exception → retryOrThrow() → paywall/error dialog
 *     → scheduleAppShutdown() → delayed task → System.exit(0)
 *
 * Both entry points call this one static method and nothing outside
 * `com.pairip.*` references `com/pairip/licensecheck` (verified across all four
 * DEXes), so replacing its body with `return-void` removes the installer check,
 * the Play licensing bind, the dialogs, the retry loop and the delayed
 * System.exit in a single edit. LicenseActivity, scheduleAppShutdown,
 * attachBaseContext and LicenseContentProvider are deliberately left untouched.
 *
 * Injection at index 0 is verifier-safe: checkLicense has `.registers 3`, no
 * try/catch region and no monitor-enter anywhere in the method, and `return-void`
 * reads no registers, so no local or parameter has to be re-established.
 */
@Suppress("unused")
val appUsagePairipPatch = bytecodePatch(
    name = "Bypass PairIP license check",
    description = "Disables PairIP (com.pairip.licensecheck) verification: no install-source " +
        "check, no Play licensing bind, no paywall/error dialog, no retry loop and no delayed " +
        "process exit. Required for sideloaded or re-signed APKs, which PairIP would otherwise " +
        "close after a few seconds.",
) {
    compatibleWith(COMPATIBILITY_APP_USAGE)

    execute {
        CheckLicenseFingerprint.method.addInstructions(0, "return-void")
    }
}

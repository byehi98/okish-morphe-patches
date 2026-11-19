package app.crazygames.patches.pairip

import app.crazygames.patches.shared.Constants.COMPATIBILITY_CRAZYGAMES
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * PairIP license-check bypass for CrazyGames (com.crazygames.crazygamesapp 1.7.13).
 *
 * PairIP here is the old Java-only variant — no libpairipcore.so, no
 * com.pairip.VMRunner, no string-integrity VM. Everything lives in classes.dex
 * (decoded to smali/smali/): com/pairip/application/Application plus the 37
 * com/pairip/... classes including com/pairip/licensecheck.
 *
 * Enforcement chain (verified in smali):
 *   Application.attachBaseContext()          (Application.smali:19)  ─┐
 *   LicenseContentProvider.onCreate()  (LicenseContentProvider.smali:95) ─┴→
 *   LicenseClient.checkLicense(Context)      (LicenseClient.smali:423)
 *     → posts LicenseClient$$ExternalSyntheticLambda7 to mainThreadRunner (:469)
 *       → lambda$checkLicense$0 (:1009) → initializeLicenseCheck() (:2272)
 *     → performLocalInstallerCheck()
 *       (installer package must be com.android.vending → false on sideload)
 *     → bind Play licensing service → bind failure or responseCode 2 (NOT_LICENSED)
 *     → startPaywallActivity()/startErrorDialogActivity()  (LicenseActivity paywall)
 *     → scheduleAppShutdown() → delayedTaskExecutor.schedule(System.exit(0), 30 s)
 *
 * Both entry points call this one static method, and initializeLicenseCheck() has
 * no other caller, so replacing its body with `return-void` removes the installer
 * check, the Play licensing bind, the paywall and the delayed System.exit in a
 * single edit. LicenseActivity, scheduleAppShutdown, attachBaseContext and
 * LicenseContentProvider are deliberately left untouched.
 *
 * Injection at index 0 is verifier-safe: checkLicense has `.locals 2` (3 registers,
 * p0 == v2), no try/catch region and no monitor-enter anywhere in the method, and
 * `return-void` reads no registers, so no local or parameter has to be
 * re-established.
 */
@Suppress("unused")
val crazyGamesPairipPatch = bytecodePatch(
    name = "Bypass PairIP license check",
    description = "Disables PairIP (com.pairip.licensecheck) verification: no install-source " +
        "check, no Play licensing bind, no paywall and no delayed process exit. " +
        "Required for sideloaded or re-signed APKs, which PairIP would otherwise close " +
        "after 30 seconds with LicenseActivity plus System.exit(0).",
) {
    compatibleWith(COMPATIBILITY_CRAZYGAMES)

    execute {
        CheckLicenseFingerprint.method.addInstructions(0, "return-void")
    }
}

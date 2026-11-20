package app.appusage.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── PairIP license check (App Usage 6.19) ────────────────────────────────────
// App Usage ships the OLD, Java-only PairIP variant: only
// com/pairip/application/Application and com/pairip/licensecheck/*, no
// libpairipcore.so and no com.pairip.VMRunner. So there is no native license VM
// to fight — the entire subsystem is plain DEX bytecode, and every entry point
// converges on one static method.
//
// Verified call sites of checkLicense(Context) across all four DEXes:
//   classes2/com/pairip/application/Application.smali:19   attachBaseContext
//   classes2/com/pairip/licensecheck/LicenseContentProvider.smali:95   onCreate
// and initializeLicenseCheck() is only ever reached from
// LicenseClient.lambda$checkLicense$0 (LicenseClient.smali:1017), i.e. from the
// Runnable that this same checkLicense posts. No other caller exists.

/**
 * `Lcom/pairip/licensecheck/LicenseClient;->checkLicense(Landroid/content/Context;)V`
 *
 * smali: classes2/com/pairip/licensecheck/LicenseClient.smali:423
 *   `.method public static checkLicense(Landroid/content/Context;)V` with
 *   `.registers 3` (v0..v1 locals, p0 == v2 == Context).
 *
 * Body (line numbers in that file), which is exactly what the filters below pin:
 *
 *   435  const-string v0, "LicenseClient"
 *   437  if-nez p0, :cond_a
 *   440  const-string p0, "Cannot check license with null context."   → Log->w
 *   447  :cond_a
 *   448  invoke-static {}, LicenseClient->isIsolatedProcess()Z
 *   452  if-eqz v1, :cond_16
 *   455  const-string p0, "Skipping license check in isolated process."  → Log->i
 *   462  :cond_16
 *   463  sget-object v0, LicenseClient->mainThreadRunner:...$ImmediateTaskExecutor
 *   465  new-instance v1, LicenseClient$$ExternalSyntheticLambda7
 *   469  invoke-interface {v0, v1}, ...$ImmediateTaskExecutor->run(Ljava/lang/Runnable;)V
 *   471  :goto_20 / 472 return-void
 *
 * When this path runs it calls initializeLicenseCheck(), which does
 * performLocalInstallerCheck() — the installer package must be Play. On a
 * sideloaded/re-signed APK that is false, licenseCheckState stays
 * CHECK_REQUIRED, the Play licensing service bind fails or answers
 * responseCode == 2 (NOT_LICENSED), and the client calls startPaywallActivity /
 * startErrorDialogActivity and scheduleAppShutdown(), which schedules
 * System.exit(0) 30 s later. So one no-op here disables the whole subsystem and
 * stops the delayed process kill.
 *
 * MATCH STRATEGY — definingClass/name are PairIP SDK identifiers (not R8 output:
 * the package is untouched in every PairIP build), and the filters are fallbacks
 * that also pin the shape, so a vendor renaming would fail loudly instead of
 * silently patching the wrong method. The filter list is in EXACT smali
 * instruction order — the second marker (`isIsolatedProcess`) sits BETWEEN the
 * two log strings at 448, not after both:
 *
 *   440  "Cannot check license with null context."
 *   448  LicenseClient->isIsolatedProcess()Z
 *   455  "Skipping license check in isolated process."
 *
 * Neither string nor the call appears in `checkLicenseInternal(IBinder)`, in
 * `initializeLicenseCheck()`, or in any of the `lambda$...` methods of this
 * class, so `checkLicense(Context)` is the only possible match. Filters are also
 * strictly stronger than the signature: `lambda$checkLicense$0(Context)` shares
 * the parameter but is private static synthetic and returns V with no such body.
 */
object CheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("Cannot check license with null context."),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "isIsolatedProcess",
            returnType = "Z",
        ),
        string("Skipping license check in isolated process."),
    ),
)

package app.appusage.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── PairIP license check (App Usage 6.20) ────────────────────────────────────
// App Usage ships the Java-only PairIP variant: only
// com/pairip/application/Application and com/pairip/licensecheck/*, no
// libpairipcore.so and no com.pairip.VMRunner (the merged APK contains no .so
// files at all). So there is no native license VM to fight — the entire
// subsystem is plain DEX bytecode, and every entry point converges on one
// static method.
//
// Verified call sites of checkLicense(Context) across all four DEXes:
//   classes2/com/pairip/application/Application.smali:19           attachBaseContext
//   classes2/com/pairip/licensecheck/LicenseContentProvider.smali:95   onCreate
// and nothing outside `com/pairip/*` references `com/pairip/licensecheck` at
// all. Inside the package, the posted Runnable (LicenseClient$$ExternalSyntheticLambda11)
// is the only thing that reaches initializeLicenseCheck().

/**
 * `Lcom/pairip/licensecheck/LicenseClient;->checkLicense(Landroid/content/Context;)V`
 *
 * smali: classes2/com/pairip/licensecheck/LicenseClient.smali:510
 *   `.method public static checkLicense(Landroid/content/Context;)V` with
 *   `.registers 3` (v0..v1 locals, p0 == v2 == Context).
 *
 * Body (line numbers in that file), which is exactly what the filters below pin:
 *
 *   524  const-string v0, "LicenseClient"
 *   526  if-nez p0, :cond_a
 *   527  const-string p0, "Cannot check license with null context."   → Log->w
 *   534  :cond_a
 *   535  invoke-static {}, LicenseClient->isIsolatedProcess()Z
 *   539  if-eqz v1, :cond_16
 *   542  const-string p0, "Skipping license check in isolated process."  → Log->i
 *   549  :cond_16
 *   550  sget-object v0, LicenseClient->mainThreadRunner:...$ImmediateTaskExecutor
 *   552  new-instance v1, LicenseClient$$ExternalSyntheticLambda11
 *   556  invoke-interface {v0, v1}, ...$ImmediateTaskExecutor->run(Ljava/lang/Runnable;)V
 *   558  :goto_20 / 559 return-void
 *
 * When this path runs it calls initializeLicenseCheck(), which in 6.20 does
 * isLocalCheckPassed()/performLocalInstallerCheck() — the installer package must
 * be Play. On a sideloaded/re-signed APK that is false, the Play licensing bind
 * fails or answers NOT_LICENSED, and the client retries through retryOrThrow(),
 * shows the paywall/error dialog and calls scheduleAppShutdown(), which exits the
 * process from a delayed task. So one no-op here disables the whole subsystem.
 *
 * MATCH STRATEGY — definingClass/name are PairIP SDK identifiers (not R8 output:
 * the package is untouched in every PairIP build), and the filters are fallbacks
 * that also pin the shape, so a vendor renaming would fail loudly instead of
 * silently patching the wrong method. The filter list is in EXACT smali
 * instruction order — the second marker (`isIsolatedProcess`) sits BETWEEN the
 * two log strings at 535, not after both:
 *
 *   527  "Cannot check license with null context."
 *   535  LicenseClient->isIsolatedProcess()Z
 *   542  "Skipping license check in isolated process."
 *
 * `isIsolatedProcess` is also called from initializeLicenseCheck() and one other
 * private method, but neither of those contains the two log strings, and
 * `checkLicenseInternal(IBinder)` / the `lambda$...` methods do not either, so
 * `checkLicense(Context)` is the only possible match. The filters are also
 * strictly stronger than the signature: the `lambda$...` methods share the
 * parameter but are private static synthetic and return V with no such body.
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

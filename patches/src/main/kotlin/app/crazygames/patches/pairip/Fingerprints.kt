package app.crazygames.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── PairIP license check (CrazyGames 1.7.13) ─────────────────────────────────
// CrazyGames ships the OLD, Java-only PairIP variant: 37 com/pairip/... classes
// (Application, licensecheck/*, LicenseActivity, ...), no libpairipcore.so and
// no com.pairip.VMRunner. The whole subsystem is plain DEX bytecode, so there is
// no native license VM to fight and every entry point converges on one static
// method.
//
// Verified call sites of checkLicense(Context) across all three DEXes:
//   smali/com/pairip/application/Application.smali:19          attachBaseContext
//   smali/com/pairip/licensecheck/LicenseContentProvider.smali:95   onCreate
// and initializeLicenseCheck() (LicenseClient.smali:2272) is only ever reached
// from lambda$checkLicense$0 (LicenseClient.smali:1009, the call is at :1017),
// i.e. from the Runnable that this same checkLicense posts. No other caller.
//
// Note on paths: the PairIP classes sit in classes.dex, so apktool decodes them
// to `smali/smali/...` (smali/ = classes.dex, smali_classes2/ = classes2.dex).

/**
 * `Lcom/pairip/licensecheck/LicenseClient;->checkLicense(Landroid/content/Context;)V`
 *
 * smali: smali/smali/com/pairip/licensecheck/LicenseClient.smali:423
 *   `.method public static checkLicense(Landroid/content/Context;)V` with
 *   `.locals 2` (v0..v1 locals + p0 == v2, so 3 registers total).
 *   No `.catch` directive and no `monitor-enter` anywhere in the file.
 *
 * Body (line numbers in that file), which is exactly what the filters below pin:
 *
 *   435  const-string v0, "LicenseClient"
 *   437  if-nez p0, :cond_0
 *   440  const-string p0, "Cannot check license with null context."   → Log->w
 *   446  :cond_0
 *   448  invoke-static {}, LicenseClient->isIsolatedProcess()Z
 *   450  move-result v1
 *   452  if-eqz v1, :cond_1
 *   455  const-string p0, "Skipping license check in isolated process."  → Log->i
 *   461  :cond_1
 *   463  sget-object v0, LicenseClient->mainThreadRunner:...$ImmediateTaskExecutor
 *   465  new-instance v1, LicenseClient$$ExternalSyntheticLambda7
 *   469  invoke-interface {v0, v1}, ...$ImmediateTaskExecutor->run(Ljava/lang/Runnable;)V
 *   471  :goto_0
 *   472  return-void
 *   473  .end method
 *
 * When this path runs it reaches initializeLicenseCheck(), which does
 * performLocalInstallerCheck() — the installer package must be Play. On a
 * sideloaded/re-signed APK that is false, licenseCheckState stays
 * CHECK_REQUIRED, the Play licensing service bind fails or answers
 * responseCode == 2 (NOT_LICENSED), and the client calls startPaywallActivity /
 * startErrorDialogActivity (LicenseActivity) and scheduleAppShutdown(), which
 * schedules System.exit(0) 30 s later (EVENTUAL_SHUTDOWN_DELAY_MILLIS). So one
 * no-op here disables the whole subsystem and stops the delayed process kill.
 *
 * MATCH STRATEGY — definingClass/name are PairIP SDK identifiers (not R8 output:
 * the package is untouched in every PairIP build), and the filters are fallbacks
 * that also pin the shape, so a vendor renaming would fail loudly instead of
 * silently patching the wrong method. The filter list is in EXACT smali
 * instruction order — `isIsolatedProcess` sits BETWEEN the two log strings
 * (line 448), not after both:
 *
 *   440  "Cannot check license with null context."
 *   448  LicenseClient->isIsolatedProcess()Z
 *   455  "Skipping license check in isolated process."
 *
 * Neither string appears anywhere else in the APK (verified: single hit each,
 * both in this method), isIsolatedProcess() has only two other call sites
 * (checkTrialStatus at :644 and stopTrial at :2141) and those do not carry the
 * strings, and no other class declares checkLicense(Context). Filters are also
 * strictly stronger than the signature: lambda$checkLicense$0(Context) shares
 * the parameter but is private static synthetic and has none of these markers.
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

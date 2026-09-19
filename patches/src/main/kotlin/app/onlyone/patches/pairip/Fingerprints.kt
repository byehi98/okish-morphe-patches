package app.onlyone.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── PairIP license check (1.3040, classes2.dex) ───────────────────────────────
// PairIP keeps its package/class/method names stable across versions because
// the manifest references them BY NAME (application android:name,
// LicenseActivity) — same reason the Corona billing plugin keeps its names.
// Verified against 1.3040 by baksmali-disassembling classes2.dex from
// `analysis/com.rebelbinary.onlyone/apk/only-one-1-3040 (1).xapk`
// (versionCode 89, targetSdk 36). All PairIP code is confined to classes2.dex:
// zero `pairip` strings in classes.dex / classes3.dex / classes4.dex, and no
// SignatureCheck / IntegrityCheck / VMRuntime components exist in this build.
//
// 1.3040 entry chain (drift vs 1.2983 notes):
//   Manifest application = `com.pairip.application.Application` (was plain
//   MultiDexApplication) → attachBaseContext → LicenseClient.checkLicense()
//   → executor → lambda$checkLicense$0 → getInstance → initializeLicenseCheck().
//   The manifest no longer registers LicenseContentProvider, but the class
//   still exists in dex and now delegates to the same static checkLicense.

/**
 * [LicenseClient.checkLicense(Context)][LicenseClient] — the single static
 * entry of the whole PairIP chain in 1.3040. Verified shape
 * (classes2/.../LicenseClient.smali `checkLicense`):
 *
 *   const-string "LicenseClient"
 *   if-null p0 → Log.w("Cannot check license with null context.") + return
 *   isIsolatedProcess() → Log.i("Skipping license check in isolated process.") + return
 *   mainThreadRunner.run(new Lambda7(context))   // → initializeLicenseCheck
 *
 * Neutering this kills BOTH live entries (Application.attachBaseContext and
 * LicenseContentProvider.onCreate) in one shot.
 */
object LicenseClientCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("Cannot check license with null context."),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "isIsolatedProcess"
        ),
        string("Skipping license check in isolated process.")
    )
)

/**
 * [LicenseClient.initializeLicenseCheck()][LicenseClient] — the instance-level
 * fan-out (still `public ...()V` in 1.3040). Verified shape (classes2 smali,
 * code-layout order):
 *
 *   sget licenseCheckState → ordinal() switch
 *   ... → initiateFreshLicensingServiceConnection (fresh-check branch)
 *   ... → LicenseResponseHelper.validateResponse (cached-response branch)
 *   ... → handleError (validation-failure catch)
 *   ... → backgroundRunner (local installer check) / initiateFresh (tail)
 *
 * Defense-in-depth alongside checkLicense: with this neutered there is no
 * service bind, no CHECK_LICENSE transact, no paywall/error dialog, no
 * repeated checks — even if some path ever reaches it directly.
 */
object LicenseClientInitCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(smali = "Lcom/pairip/licensecheck/LicenseClient;->licenseCheckState:Lcom/pairip/licensecheck/LicenseClient\$LicenseCheckState;"),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;",
            name = "validateResponse"
        ),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "handleError"
        )
    )
)

/**
 * [LicenseContentProvider.onCreate()][LicenseContentProvider] — legacy
 * bootstrap. In 1.3040 it is `getContext() → LicenseClient.checkLicense() →
 * return true` (in 1.2983 it built a LicenseClient instance directly) and is
 * no longer registered in the manifest, but the class ships in dex so it is
 * neutered anyway as belt-and-braces.
 */
object LicenseContentProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseContentProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "checkLicense"
        )
    )
)

/**
 * [LicenseClient$1.run()][LicenseClient$1] — the exitAction Runnable behind
 * LicenseActivity.closeApp()/exitApp(). Verified body (unchanged from notes):
 * `const/4 v0, 0x0; System.exit(v0); return-void`. Neutered so `System.exit(0)`
 * can never fire even if an error/paywall dialog path is ever reached.
 */
object LicenseClientExitActionFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient\$1;",
    name = "run",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/lang/System;",
            name = "exit"
        )
    )
)

package app.opheliasparadise.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * PairIP license-check chokepoints for Ophelia's Paradise 26.11.15 (versionCode 9003).
 *
 * All PairIP code lives in `classes2.dex` (38 KB) and is the plain Java flavor:
 * no `com.pairip.SignatureCheck`, no `VMRunner`, no `libpairipcore.so`, no
 * virtualised methods — verified by scanning every dex for `pairipcore` / `VMRunner`
 * (0 hits) and by the class inventory of the `com/pairip` package.
 *
 * Enforcement flow (verified in smali):
 *   com.pairip.application.Application.attachBaseContext
 *     → LicenseClient.checkLicense(Context)                    [T1 — static entry]
 *       → ImmediateTaskExecutor.run(lambda$checkLicense$0)
 *         → initializeLicenseCheck()                            [T2 — state-machine fan-out]
 *           → bind Play licensing service / processResponse     [T3]
 *             → LicenseResponseHelper.validateResponse          [T4 — JWS/RSA validation]
 *               → on failure: handleError → LicenseActivity      [T6 — paywall/error dialog]
 *                 → LicenseClient$1.exitAction → System.exit(0)  [T5 — forced exit]
 *   LicenseContentProvider.onCreate → checkLicense              [T7 — provider bootstrap]
 *
 * Names are library names (`.source "LicenseClient.java"`), not obfuscated, so the
 * definingClass/name pairs are stable.
 *
 * 26.11.15 (SDK bump, same plain Java flavor) added a trial layer on top:
 * `TrialClient.stopTrial / checkTrialStatus`, `TrialStatus`, and a graceful-shutdown
 * path (`LicenseClient.gracefulShutdownEnabled` → `closeAllTasks`). None of it is
 * reachable once T1/T2 are neutered — `licenseCheckState` stays `CHECK_REQUIRED`, so
 * `TrialClient.checkTrialStatus` returns `UNKNOWN`/`NOT_UNDER_TRIAL` and no shutdown
 * task is ever scheduled. `exitAction` (`LicenseClient$1`) is unchanged.
 */

/**
 * T1 — primary entry: `public static checkLicense(Landroid/content/Context;)V`
 * (`LicenseClient.smali:423`, `.registers 3`).
 *
 * `return-void` at offset 0 kills the whole flow before singleton creation, service
 * bind, dialog or exit timer. This single hook covers BOTH live entries
 * (`Application.attachBaseContext` and `LicenseContentProvider.onCreate`).
 *
 * Filter order matches the physical instruction stream:
 * null-context log → isIsolatedProcess() → isolated-process log → executor.run().
 */
object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("Cannot check license with null context."),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient;", name = "isIsolatedProcess"),
        string("Skipping license check in isolated process."),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient\$ImmediateTaskExecutor;",
            name = "run"
        ),
    ),
)

/**
 * T2 — re-entry: `public initializeLicenseCheck()V`
 * (`LicenseClient.smali:2258`, `.registers 4`).
 *
 * Covers a direct instance call even if T1 is somehow bypassed.
 *
 * Physical filter order: `sget-object licenseCheckState` → `ordinal()` →
 * `validateResponse` (cached-response branch) → `handleError` (catch block).
 * `OpcodesFilter` does exact opcode matching, so `Opcode.SGET_OBJECT` is required —
 * a plain `Opcode.SGET` would never match.
 */
object PairipInitializeLicenseCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "licenseCheckState"
        ),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient\$LicenseCheckState;",
            name = "ordinal"
        ),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;", name = "validateResponse"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient;", name = "handleError"),
    ),
)

/**
 * T3 — signed-response callback: `processResponse(ILandroid/os/Bundle;)V`
 * (`LicenseClient.smali:1720`, `.registers 5`).
 *
 * 26.11.15 note: the SDK bump promoted this from `private` to `public`, which is why
 * this fingerprint deliberately carries no accessFlags/parameters.
 *
 * No accessFlags/parameters so the fingerprint stays tolerant of R8 flag drift; the
 * name is unique (`-$$Nest$mprocessResponse` and `lambda$processResponse$0` differ).
 * Neutered with a bare `return-void` — the response code never reaches
 * `validateResponse`.
 */
object PairipProcessResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "processResponse",
    returnType = "V",
)

/**
 * T4 — JWS/RSA validation: `public static validateResponse(Landroid/os/Bundle;Ljava/lang/String;)V`
 * (`LicenseResponseHelper.smali:361`, `.registers 7`).
 *
 * Also disables the `FULL_CHECK_OK` re-validation branch inside
 * `initializeLicenseCheck`. `validateResponse` throws `LicenseCheckException` on any
 * re-signed payload, which is what drives `handleError`.
 */
object PairipValidateResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;",
    name = "validateResponse",
    returnType = "V",
)

/**
 * T5 — exit killer: `public run()V` on the anonymous `LicenseClient$1` Runnable
 * (`LicenseClient$1.smali`, `.registers 1`) — body is `System.exit(0); return-void`.
 *
 * This is the `exitAction` scheduled by `scheduleAppShutdown()` with a 30 s delay
 * (`0x7530` ms), so neutering it makes the "app dies half a minute later" behaviour
 * impossible even if an error path is ever reached.
 */
object PairipExitActionFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient\$1;",
    name = "run",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/System;", name = "exit"),
    ),
)

/**
 * T6 — paywall/error screen: `public onStart()V`
 * (`LicenseActivity.smali:426`, `.registers 3`).
 *
 * Physical order in this build: `Activity.onStart()` → `"activitytype"` extra →
 * `showErrorDialog()` → `showPaywallAndCloseApp()`. Returning at offset 0 makes the
 * activity inert if it is ever launched.
 */
object PairipLicenseActivityOnStartFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseActivity;",
    name = "onStart",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Activity;", name = "onStart"),
        string("activitytype"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseActivity;", name = "showErrorDialog"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseActivity;", name = "showPaywallAndCloseApp"),
    ),
)

/**
 * T7 — provider bootstrap: `public onCreate()Z`
 * (`LicenseContentProvider.smali`, `.registers 1` → `v0` == `p0`).
 *
 * The manifest registers `LicenseContentProvider`, and its Phase-2 licence check runs
 * before `Application.onCreate`. Report success immediately.
 */
object PairipContentProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseContentProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient;", name = "checkLicense"),
    ),
)

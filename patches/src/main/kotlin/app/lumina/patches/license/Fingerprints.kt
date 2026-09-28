package app.lumina.patches.license

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * PairIP license-check chokepoints for Lumina Wallpapers 1.0.2.6 (all non-obfuscated,
 * classes2.dex — `analysis/lumina/smali/classes2/com/pairip/licensecheck/`).
 *
 * Enforcement flow (verified in smali):
 *   com.pairip.application.Application.attachBaseContext
 *     → LicenseClient.checkLicense(Context)                 [T1 — static entry]
 *       → ImmediateTaskExecutor.run(lambda$checkLicense$0)
 *         → initializeLicenseCheck()                        [T2 — state machine fan-out]
 *           → bind Play licensing service / processResponse [T3]
 *             → LicenseResponseHelper.validateResponse      [T4 — RSA signature check]
 *               → on failure: handleError → LicenseActivity  [T6 — paywall/error dialog]
 *                 → LicenseClient$1.exitAction → System.exit(0) [T5 — forced exit]
 *   LicenseContentProvider.onCreate → checkLicense          [T7 — manifest-UNREGISTERED in 1.0.2.6]
 *
 * All PairIP names below are library names (`.source "LicenseClient.java"` etc.) — not
 * app-obfuscated — so they are stable across versions and safe to name in fingerprints.
 */

/**
 * T1 — primary entry: `public static checkLicense(Landroid/content/Context;)V`
 * (`LicenseClient.smali:405`, `.registers 3`).
 *
 * `return-void` at offset 0 kills the whole flow before any singleton creation,
 * service bind, dialog or exit timer.
 *
 * NOTE (deviation from notes): `definingClass`/`name` added beyond the notes'
 * filter-only strategy (BigHunter/OnlyOne precedent) — verified exact match on the
 * smali header `.method public static checkLicense`.
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
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient\$ImmediateTaskExecutor;", name = "run"),
    ),
)

/**
 * T2 — re-entry: `public initializeLicenseCheck()V` (`LicenseClient.smali:2155`, `.registers 4`).
 *
 * Covers any caller of `getInstance(...).initializeLicenseCheck()` even if T1 is
 * bypassed by a direct instance call.
 *
 * NOTE (deviation from notes): the notes' snippet says `Opcode.SGET`, but the actual
 * instruction at `LicenseClient.smali:2159` is `sget-object` — `OpcodesFilter` does
 * exact opcode matching (no family expansion), so `Opcode.SGET_OBJECT` is required.
 * Physical filter order re-verified: sget-object (2159) → ordinal (2161) →
 * validateResponse (2192) → handleError (2202).
 */
object PairipInitializeLicenseCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = "Lcom/pairip/licensecheck/LicenseClient;", name = "licenseCheckState"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient\$LicenseCheckState;", name = "ordinal"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;", name = "validateResponse"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient;", name = "handleError"),
    ),
)

/**
 * T3 — signed-response callback: `private processResponse(ILandroid/os/Bundle;)V`
 * (`LicenseClient.smali:1610`, `.registers 6` → `p1` = `v4`).
 *
 * Verbatim from the existing (proven) Lumina premium patch: no accessFlags/parameters.
 * `-$$Nest$mprocessResponse` and `lambda$processResponse$0` have different names →
 * still unique. Patch = `const/4 p1, 0x0` + `return-void` (dead const is intentional).
 */
object PairipProcessResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "processResponse",
    returnType = "V",
)

/**
 * T4 — RSA signature validation: `public static validateResponse(Landroid/os/Bundle;Ljava/lang/String;)V`
 * (`LicenseResponseHelper.smali:360`, `.registers 7`). Verbatim from the existing patch.
 *
 * Also disables the `FULL_CHECK_OK` re-validation branch inside `initializeLicenseCheck`.
 */
object PairipValidateResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;",
    name = "validateResponse",
    returnType = "V",
)

/**
 * T5 — exit killer: `public run()V` on the anonymous `LicenseClient$1` Runnable
 * (`LicenseClient$1.smali:32`, `.registers 2`) — body is `System.exit(0); return-void`.
 * Even if an error path is reached, the forced exit becomes a no-op.
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
 * T6 — paywall/error screen: `public onStart()V` (`LicenseActivity.smali:421`, `.registers 3`).
 *
 * NOTE (deviation from notes): the notes list `showPaywallAndCloseApp` before
 * `showErrorDialog`, but the physical instruction order is reversed —
 * `showErrorDialog` (`:cond_19`, LicenseActivity.smali:456) executes in the
 * instruction stream BEFORE `showPaywallAndCloseApp` (`:cond_1d`, line 462).
 * Ordered filters scan strictly forward, so the filters are swapped here to match
 * the smali order exactly.
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
 * T7 — legacy provider bootstrap: `public onCreate()Z`
 * (`LicenseContentProvider.smali:87`, `.registers 2` → `v0` free).
 *
 * NOT in the 1.0.2.6 manifest (class still shipped in dex) → never instantiated in
 * this build; included for parity with the 4-hook recipe / OnlyOne precedent and
 * future-proofing if the provider is re-registered. Harmless either way.
 * `LicenseContentProvider1` (subclass) has no `onCreate` override → unique match.
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

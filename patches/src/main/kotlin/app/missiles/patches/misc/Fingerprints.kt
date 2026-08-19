package app.missiles.patches.misc

import app.morphe.patcher.Fingerprint

// ── Pairip license check ──────────────────────────────────────────────────────

/**
 * initializeLicenseCheck — public entry point called by the app on startup.
 * Return-void to skip the entire check pipeline.
 */
object PairipInitializeLicenseCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    parameters = listOf()
)

/**
 * performLocalInstallerCheck — returns true if installed from Play Store.
 * Return false to skip it (defense-in-depth).
 */
object PairipLocalInstallerCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "performLocalInstallerCheck",
    returnType = "Z",
    parameters = listOf()
)

/**
 * processResponse — called with the server license response code.
 * responseCode 0 = LICENSED. We force p1=0 so it always takes the success path.
 */
object PairipProcessResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "processResponse",
    returnType = "V",
    parameters = listOf("I", "Landroid/os/Bundle;")
)

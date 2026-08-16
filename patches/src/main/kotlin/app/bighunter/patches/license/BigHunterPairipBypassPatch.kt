package app.bighunter.patches.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.bighunter.patches.shared.Constants.COMPATIBILITY_BIG_HUNTER

/**
 * PairIP license-check bypass for Big Hunter.
 *
 * PairIP (com.pairip.licensecheck) starts before the game via a manifest-registered
 * ContentProvider, verifies the install came from Google Play, binds Play's licensing
 * service, validates the signed response against a pinned RSA key, and on any failure
 * launches a paywall/error dialog LicenseActivity that force-exits the app
 * (exitAction = System.exit(0)). On a patched/re-signed APK this check ALWAYS fails.
 *
 * Triple defense-in-depth, matching the community PairIP pattern (hoo-dles/morphe-patches):
 *  1. initializeLicenseCheck() returns immediately — the check never starts (primary).
 *  2. ResponseValidator.validateResponse() returns immediately — even if some other path
 *     reaches validation, it never throws LicenseCheckException.
 *  3. LicenseActivity.onStart() returns immediately — if the paywall activity is ever
 *     launched anyway, it is inert instead of showing a dialog and killing the app.
 *
 * There is no com.pairip.validation (string-integrity) package in this app — the
 * licensecheck family above is the complete PairIP surface here.
 */
@Suppress("unused")
val bigHunterPairipBypassPatch = bytecodePatch(
    name = "Big Hunter PairIP License Bypass",
    description = "Disables the PairIP license check (installer verification + Google Play licensing service validation + paywall/exit dialog). Required for patched APKs — the original check fails on any non-Play signature and force-closes the app.",
) {
    compatibleWith(COMPATIBILITY_BIG_HUNTER)

    execute {
        InitializeLicenseCheckFingerprint.method.addInstructions(0, "return-void")
        ValidateResponseFingerprint.method.addInstructions(0, "return-void")
        LicenseActivityOnStartFingerprint.method.addInstructions(0, "return-void")
    }
}

package app.lumina.patches.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.lumina.patches.shared.Constants.COMPATIBILITY_LUMINA

/**
 * Lumina Wallpapers — PairIP license bypass.
 *
 * PairIP (com.pairip.licensecheck, classes2.dex) runs before the app on every cold
 * start: Application.attachBaseContext → LicenseClient.checkLicense(Context) →
 * binds Play's licensing service, validates the signed response with RSA
 * (LicenseResponseHelper.validateResponse), and on any failure launches a paywall /
 * error-dialog LicenseActivity that force-exits via System.exit(0)
 * (LicenseClient$1.exitAction). On a patched/re-signed APK this check ALWAYS fails.
 *
 * Seven hooks (notes T1–T7), all `return-void` / constant-return at offset 0 — no
 * register subtleties (register counts verified per method in the notes):
 *  1. T1 checkLicense → return-void: the single live entry point — nothing runs at all.
 *  2. T2 initializeLicenseCheck → return-void: covers re-entry via a direct instance call.
 *  3. T5 LicenseClient$1.run → return-void: System.exit(0) can never fire.
 *  4. T6 LicenseActivity.onStart → return-void: the paywall/error screen is inert.
 *  5. T7 LicenseContentProvider.onCreate → return true: legacy provider bootstrap —
 *     unregistered in the 1.0.2.6 manifest (class still shipped), included for parity
 *     with the 4-hook OnlyOne/BigHunter pattern; harmless if never instantiated.
 *  6. T3 LicenseClient.processResponse → const/4 p1, 0x0 + return-void: binder
 *     response callback neutered (verbatim from the existing proven patch).
 *  7. T4 LicenseResponseHelper.validateResponse → return-void: signed-response
 *     validation neutered (verbatim from the existing proven patch; also disables
 *     the FULL_CHECK_OK re-validation branch inside initializeLicenseCheck).
 *
 * `default = true` — the app cannot run without this (same rationale as the OnlyOne
 * PairIP and Missiles license patches).
 */
@Suppress("unused")
val luminaPairipBypassPatch = bytecodePatch(
    name = "Lumina PairIP License Bypass",
    description = "Disables the PairIP license check (Play licensing service validation + signed-response verification + paywall/error dialog + forced System.exit). Required for patched APKs — the original check fails on any non-Play signature and force-closes the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LUMINA)

    execute {
        // 1. Static entry — nothing downstream ever starts.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")

        // 2. Instance fan-out — no service bind / transact / re-checks.
        PairipInitializeLicenseCheckFingerprint.method.addInstructions(0, "return-void")

        // 3. Exit action — System.exit(0) never fires.
        PairipExitActionFingerprint.method.addInstructions(0, "return-void")

        // 4. Paywall / error-dialog screen — inert if ever launched.
        PairipLicenseActivityOnStartFingerprint.method.addInstructions(0, "return-void")

        // 5. Legacy provider bootstrap — immediately report success.
        PairipContentProviderOnCreateFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // 6. Signed-license-response callback — p1 = v4 (.registers 6); dead
        //    const/4 is intentional (verbatim from the proven existing patch).
        PairipProcessResponseFingerprint.method.addInstructions(0, """
            const/4 p1, 0x0
            return-void
        """.trimIndent())

        // 7. RSA response validation — never throws LicenseCheckException.
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")
    }
}

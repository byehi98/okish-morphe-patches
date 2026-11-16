package app.opheliasparadise.patches.pairip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.opheliasparadise.patches.shared.Constants.COMPATIBILITY_OPHELIAS_PARADISE

/**
 * Ophelia's Paradise — PairIP license bypass.
 *
 * The demo ships Google's PairIP ("app protection") in the plain Java flavor:
 *
 *   AndroidManifest android:name="com.pairip.application.Application"
 *     attachBaseContext → LicenseClient.checkLicense(Context)
 *   com/pairip/licensecheck/LicenseContentProvider.onCreate → checkLicense
 *   com/pairip/licensecheck/LicenseClient  → binds Play licensing, validates the
 *     signed response, and on failure launches LicenseActivity (paywall / "Something
 *     went wrong") which force-exits the process.
 *
 * There is NO layer 3 here (unlike 20 Minutes Till Dawn): scanning every dex for
 * `pairipcore` and `VMRunner` returns 0 hits, there is no
 * `com/pairip/SignatureCheck`, and no virtualised methods — so neutering the license
 * entry points is sufficient. No crypto or native workaround needed.
 *
 * Behaviour on a re-signed build without this patch: the paywall/error dialog appears
 * and `LicenseClient$1` calls `System.exit(0)` 30 s later (the delayed executor is
 * scheduled with `0x7530` ms).
 *
 * Seven hooks, all `return-void` / constant-return at instruction index 0. Register
 * counts were verified per method in the baksmali output, so none of them needs a
 * scratch register except `LicenseContentProvider.onCreate` (which already carries
 * the boolean result in `v0`).
 *
 * Verified against 26.11.15 (9003): all seven fingerprints still match unchanged; the
 * only SDK drift is `processResponse` becoming `public` and the new trial classes,
 * both of which are already covered (see Fingerprints.kt).
 *
 * `default = true` — the app cannot run past the licence gate without it.
 */
@Suppress("unused")
val pairipBypassPatch = bytecodePatch(
    name = "Bypass PairIP license check",
    description = "Disables the PairIP licence check (Play licensing service, signed-response " +
        "validation, paywall/error dialog and the delayed System.exit) so the re-signed app " +
        "launches instead of closing itself. Required for every other Ophelia's Paradise patch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_OPHELIAS_PARADISE)

    execute {
        // 1. Static entry — kills the whole chain for BOTH Application.attachBaseContext
        //    and LicenseContentProvider.onCreate.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")

        // 2. Instance fan-out — no service bind, no transact, no re-checks.
        PairipInitializeLicenseCheckFingerprint.method.addInstructions(0, "return-void")

        // 3. Binder response callback — never reaches validateResponse.
        PairipProcessResponseFingerprint.method.addInstructions(0, "return-void")

        // 4. JWS/RSA response validation — never throws LicenseCheckException.
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")

        // 5. Exit action — the 30 s delayed System.exit(0) becomes a no-op.
        PairipExitActionFingerprint.method.addInstructions(0, "return-void")

        // 6. Paywall / error screen — inert if it is ever launched.
        PairipLicenseActivityOnStartFingerprint.method.addInstructions(0, "return-void")

        // 7. Provider bootstrap — immediately report success.
        PairipContentProviderOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """.trimIndent()
        )
    }
}

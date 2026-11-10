package app.dumbbellworkout.patches.firebase

import app.dumbbellworkout.patches.shared.Constants.COMPATIBILITY_DUMBBELL_WORKOUT
import app.dumbbellworkout.patches.shared.Constants.ORIGINAL_SHA1_HEX_UPPER
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * Dumbbell Workout at Home — Firebase certificate-header spoof
 * (`dumbbellworkout.dumbbellapp.homeworkout` 1.3.6 / versionCode 52).
 *
 * ## What this fixes
 *
 * On a re-signed APK every Firebase/GMS request is rejected:
 *
 * ```
 * W/Firebase-Installations: Error when communicating with the Firebase
 *   Installations server API. HTTP response: [403 Forbidden: {
 *     "reason": "API_KEY_ANDROID_APP_BLOCKED",
 *     "metadata": { "androidPackage": "dumbbellworkout...", "consumer": "projects/906238731334" } }]
 * ```
 *
 * The project's API key is restricted to the Play build's package + signing
 * certificate, so Installations, Remote Config, Auth and Storage stop working
 * on a patched build.
 *
 * **This is *not* the cause of the Start-workout crash.** The workout plan →
 * asset map (`a96.f518h`) is populated locally at startup from the hardcoded
 * table in `ye1.m45659x`; the crash was the second native signature gate in
 * `liblpcore.so` that made `decryptDataAndConvertToString` return `null` (see
 * `SignatureSpoofPatch` and `notes/findings.md` §2.3). This patch is still
 * required for Firebase-backed functionality to work on a re-signed build.
 *
 * ## The patch
 *
 * Every Firebase/GMS request carries `X-Android-Package` plus `X-Android-Cert`,
 * where the latter is the SHA-1 of the APK's signing certificate, hex-encoded by
 * one method: `Lcom/google/android/gms/common/util/Hex;->b([B)Ljava/lang/String;`
 * (Remote Config, Installations, Auth, and both Auth web flows all call it — see
 * [CertHexFingerprint]). Forcing it to return the **original** certificate's
 * SHA-1 makes the API-key check see the Play build, so Installations/Remote
 * Config/Auth/Storage requests are accepted again.
 *
 * Registers: `Hex.b` is static with `.registers 6` and a single `byte[]`
 * parameter (`p0 == v5`), so `v0` is a free local; the injected `const-string`
 * cannot clobber the parameter.
 *
 * ## What it does *not* do
 *
 * It does not make Google Sign-In / Backup & Restore work. GMS attests the
 * caller's identity to Google's auth servers out-of-process, and the OAuth
 * client is registered against the developer's certificate; that check is
 * server-side and unspoofable from the APK (see `notes/findings.md` §2.4).
 */
@Suppress("unused")
val dumbbellFirebaseCertSpoofPatch = bytecodePatch(
    name = "Firebase Cert Spoof",
    description = "Restores Firebase requests on a re-signed APK. The Firebase API key is " +
        "restricted to the Play build's package + signing certificate, so Installations, Remote " +
        "Config, Auth and Storage requests from a patched build are rejected with " +
        "API_KEY_ANDROID_APP_BLOCKED. This patch forces the certificate hash Firebase sends in " +
        "X-Android-Cert to the original signing certificate's SHA-1, so those requests are " +
        "accepted again. It does not fix the Start-workout crash — that was a native " +
        "asset-decryption check covered by the Signature Spoof patch. It also does not make " +
        "Google Sign-In work — that is validated server-side against the developer's OAuth " +
        "client and cannot be patched (use the unpatched app for cloud sync).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DUMBBELL_WORKOUT)

    execute {
        val matches = CertHexFingerprint.matchAll()
        if (matches.size != 1) {
            throw PatchException(
                "Firebase Cert Spoof: expected exactly one cert-hex encoder" +
                    " (com/google/android/gms/common/util/Hex;->b([B)Ljava/lang/String;), found" +
                    " ${matches.size}:" +
                    matches.joinToString(prefix = " ", separator = ", ") { it.method.toString() } +
                    ". Firebase's request signing changed — refusing to emit an APK that would" +
                    " still crash when a workout is started."
            )
        }

        matches.single().method.addInstructions(
            0,
            """
                const-string v0, "$ORIGINAL_SHA1_HEX_UPPER"
                return-object v0
            """.trimIndent()
        )
    }
}

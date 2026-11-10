package app.dumbbellworkout.patches.firebase

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * `Lcom/google/android/gms/common/util/Hex;->b([B)Ljava/lang/String;` — the
 * **uppercase** hex encoder inside the bundled GMS library.
 *
 * Verified in smali (`smali/classes2/com/google/android/gms/common/util/Hex.smali:188`):
 *
 * ```
 * .method public static b([B)Ljava/lang/String;
 *     .registers 6
 *     array-length v0, p0
 *     ...
 *     sget-object ..., Lcom/google/android/gms/common/util/Hex;->a:[C   # '0'-'9','A'-'F'
 * ```
 *
 * It is the single point where every Firebase/GMS component turns the APK's
 * signing-certificate SHA-1 into the `X-Android-Cert` request header:
 *
 * | caller (1.3.6) | use |
 * |---|---|
 * | `p000/b71.java:226` | Firebase Remote Config fetch |
 * | `p000/os2.java:264` | Firebase Installations (`firebaseinstallations.googleapis.com`) |
 * | `ConfigFetchHttpClient.java:271` | Remote Config HTTP client |
 * | `zzaff.java:27` | Firebase Auth (`identitytoolkit`, reCAPTCHA, IDP) |
 * | `GenericIdpActivity.java:275` / `RecaptchaActivity.java:183` | Auth web flows (lower-cased afterwards) |
 *
 * No other caller exists in the APK, so overriding this method cannot affect
 * anything except the certificate header. `Hex.a([B)` (lowercase variant) and
 * `Hex.c(String)` (decoder) are separate methods and are left untouched.
 *
 * MATCH STRATEGY: the class and method names are *not* obfuscated (the GMS
 * libraries keep their original names, unlike the app's own R8-renamed code),
 * so pinning them is stable. The signature is asserted again on the method
 * (`public static String b(byte[])`) and the patch refuses to emit an APK unless
 * exactly one match is found.
 */
object CertHexFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/common/util/Hex;",
    name = "b",
    returnType = "Ljava/lang/String;",
    parameters = listOf("[B"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

package app.dumbbellworkout.patches.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DUMBBELL_WORKOUT = Compatibility(
        name = "Dumbbell Workout at Home",
        packageName = "dumbbellworkout.dumbbellapp.homeworkout",
        appIconColor = 0xB00020,
        targets = listOf(
            AppTarget(version = "1.3.6")
        )
    )

    /**
     * Uppercase SHA-1 of the *original* signing certificate's DER encoding,
     * i.e. `sha1(hex-decode(ORIGINAL_SIGNATURE_HEX))`.
     *
     * Cross-checked against the APK: `apksigner verify --print-certs` reports
     * `Signer #1 certificate SHA-1 digest: 0639d5bd10bcef3331803b61bed73ea3ae3336ec`
     * for `dumbbell-workout-at-home-1-3-6.apk`.
     *
     * Firebase and GMS build the `X-Android-Cert` request header from this digest
     * (`com.google.android.gms.common.util.AndroidUtilsLight` → `Hex.b([B)`) and
     * Google validates it against the Firebase API key's Android restrictions
     * (package + SHA-1). The header is the *only* place the certificate is
     * attested to Google, so forcing this constant makes a re-signed build look
     * like the Play build to the API-key check. Uppercase matches
     * `Hex.b()` (`Hex.a()` is the lowercase variant).
     */
    const val ORIGINAL_SHA1_HEX_UPPER = "0639D5BD10BCEF3331803B61BED73EA3AE3336EC"

    /**
     * The 1826-character lowercase hex string that
     * `Signature.toCharsString()` returned for the *original* signing certificate,
     * decoded from the APK Signing Block (v3 id `0xf05368c0`) and stored verbatim in
     * `analysis/dumbbell-workout-at-home/notes/cert-tocharsstring-hex.txt`.
     *
     * Every one of the app's 46 anti-tamper comparisons slices this exact string, so
     * returning it in full satisfies all of them. Do not reflow or shorten it — it
     * must stay a single 1826-character literal.
     */
    const val ORIGINAL_SIGNATURE_HEX = "3082038d30820275a003020102020403387919300d06092a864886f70d01010b05003076310b300906035504061302434e310e300c0603550408130548656e616e31123010060355040713095a68656e675a686f7531153013060355040a130c44726f6a69616e20536f667431153013060355040b130c44726f6a69616e20536f6674311530130603550403130c44726f6a69616e20536f66743020170d3230303531393132303032325a180f32323730303332303132303032325a3076310b300906035504061302434e310e300c0603550408130548656e616e31123010060355040713095a68656e675a686f7531153013060355040a130c44726f6a69616e20536f667431153013060355040b130c44726f6a69616e20536f6674311530130603550403130c44726f6a69616e20536f667430820122300d06092a864886f70d01010105000382010f003082010a0282010100a5fb14c7685f5de318be8519d2aa59c8726f025d79e211465c08cc86b72cf6ad673a3e18b2ad6ff5040854d8af689b62a92a3bface5fa18841221b47dac5f8510354c6ff7488d22be29502a8e80edb75b43a325bf69af1e136de806b7313bfea3e950250ad53c69e6bb8921ad51e318f37b9cef0cc59a02adc207cd7f772b6d474d1bad47dd9ded5413058cfee75a9a80beb9624b7565f26b708948c85c0cfe140c7e24a6377ebe986eef066473765e745ec2b3edda689267f00b8ced64686b4f22402ab99d55a0d6ac2e9d65b3b5624b2000ac75e6b1cdaaa250eda635d148a49a6a48ea00e57063654832a1e2f2a616db9c4db20755454d22027ab250eced30203010001a321301f301d0603551d0e0416041487e56e8029ec042457a05f6f81ee137051d0faf1300d06092a864886f70d01010b0500038201010032bdc15fc70f9b002d402134ccc9496516f842d500337e8d9f652a434307b370f7b24939e83d73e6253eb022cd095eaa6f0cf7dab69834e8831b44bbb467b34f7b2afc9035353f540339c6dcd5db36ad2eb27485ab575ab8d30535654c69eb3bda3c59b8562258d4628b8fa317771dfe8a8b632227f4e14bf0a24797561415e632a65f42b95c551c4d1297dbf2fa230eae1077c5fe8ff6ddfa5f935b45ff87e32487b0bf23acee8ed696e93636c812cf4987e8ca81911b52fb91af34c0f52b26f187af1ad1b35486846c8c819775bbf5bf38c01eb3c8a608db12fc9bb0dd6bada75ed01ca2d141a2112bf4ed857ec91f4c2b090c15416bf2b085ac7678968c28"
}

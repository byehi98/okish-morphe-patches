package app.earntodie2.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.earntodie2.patches.shared.Constants.COMPATIBILITY_EARNTODIE2

// Original Not Doppler Play Store signing cert public key (SPKI, base64 NO_WRAP).
// libEarnToDie2.so (FGKit::AndroidUtils::GetAppPublicKey) compares the value
// returned by AndroidUtils.getAppPublicKey() to this embedded key and shows
// "Invalid signature" on mismatch. Extracted from the untouched APKPure APK's
// META-INF/CERT.RSA (CN=John Daskalopoulos, O=Not Doppler Pty Limited).
private const val ORIGINAL_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAhS/trwWekY3MYOvrvWwEOG/KOEBsLhAmzWkjXPZsnVid6pgldI7wGbg2anupnwyBTomTVHNW9YZcqBsXYNt8Lc6fuWJiyPGJBEKjT/XqH4Xrbha+yKL6VTa8bZmFuHetArXnR3f/R848JQ32ktQTB02HVe/+/vYnj7JFms+cIhKbSJB4ZNOyVw8ICvtobfHMJUWDeqzFZYSJiV+oZAKY+XR3KRk6ufRylU11rnhPS9qN3HFl9j8ASCgyd/4D2MmI0iooUNtgxhS7JKFOdabgjmOkC2lC5mRCpy8l9R82xao1vQXX+AeyBR++4sQtspM5MCEjRXaLqbfTOpe5uLcrmwIDAQAB"

@Suppress("unused")
val earnToDie2GooglePlayCheckBypassPatch = bytecodePatch(
    name = "Earn to Die 2 Remove Google Play Check",
    description = "Bypasses the native Google Play distribution checks (installer package + signing signature) so the app runs when sideloaded.",
    default = true
) {
    compatibleWith(COMPATIBILITY_EARNTODIE2)

    execute {
        // 1) Signature check: native C++ (FGKit::AndroidUtils::GetAppPublicKey)
        //    calls AndroidUtils.getAppPublicKey() and compares it to the embedded
        //    original Play Store key. Our re-signed APK has a different key →
        //    "Invalid signature" dialog. Return the original key constant instead.
        //    Method has .registers 3 (v0..v2 free) — v0 valid at index 0.
        GetAppPublicKeyFingerprint.method.addInstructions(0, """
            const-string v0, "$ORIGINAL_PUBLIC_KEY"
            return-object v0
        """.trimIndent())

        // 2) Installer check: native C++ calls AndroidUtils.getInstallerPackage()
        //    and compares it to "com.android.vending". Sideloaded installs return
        //    null → "Please download the game from Google Play" dialog. Return the
        //    Play Store package constant so the check always passes.
        //    Method has .registers 2 (v0, v1 free) — v0 valid at index 0.
        GetInstallerPackageFingerprint.method.addInstructions(0, """
            const-string v0, "com.android.vending"
            return-object v0
        """.trimIndent())
    }
}

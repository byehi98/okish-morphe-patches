package app.intothedead.patches.billing

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.intothedead.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD

/**
 * Into the Dead — Verify Bypass
 *
 * com.pikpok.AndroidStore.Verify(String purchaseData, String signature, String publicKey)Z
 * performs SHA1withRSA receipt verification (classes7/com/pikpok/AndroidStore.smali:1932).
 * C# can invoke it via JNI after a purchase; if it rejects our fabricated signature the
 * grant may fail, so this patch makes Verify always return true.
 *
 * The Free IAP patch fabricates a purchase receipt with a dummy signature; this backup
 * patch ensures any Java-side receipt verification accepts it.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1932 (.registers 6).
 */
@Suppress("unused")
val intoTheDeadVerifyBypassPatch = bytecodePatch(
    name = "Into the Dead Verify Bypass",
    description = "Bypasses SHA1withRSA purchase receipt verification so fabricated purchase receipts are accepted.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD)

    execute {
        VerifyFingerprint.method.returnEarly(true)
    }
}

package app.docscanner.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.docscanner.patches.shared.Constants.COMPATIBILITY_DOCSCANNER

/**
 * Doc Scanner Premium (com.cv.docscanner 6.9.9)
 *
 * Forces the two central entitlement gates in obfuscated class bm7
 * (classes2/bm7.smali) to return true:
 *
 *  1. IsPremiumFingerprint → bm7.b() — master gate, 50 call sites (ads,
 *     paywall, pro features, Firebase pro/free topic).
 *  2. IsSubscribedFingerprint → bm7.a() — subscription gate; b() also falls
 *     through to it at :cond_47, so both are patched for robustness against
 *     future reordering/inline changes.
 *
 * Premium state is local SharedPreferences only (no server-side entitlement,
 * no LVL, no purchaseToken validation), so a forced true is permanent.
 * Side effects of the unlock: all AdMob banners removed (every ad surface
 * branches on b()), pro/custom themes enabled.
 */
@Suppress("unused")
val docScannerPremiumPatch = bytecodePatch(
    name = "Doc Scanner Premium",
    description = "Unlocks all premium features, removes ads, and enables pro themes."
) {
    compatibleWith(COMPATIBILITY_DOCSCANNER)

    execute {
        // bm7.b() — master premium gate (.registers 3 → v0 valid).
        IsPremiumFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)

        // bm7.a() — subscription gate (.registers 5 → v0 valid).
        IsSubscribedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)
    }
}

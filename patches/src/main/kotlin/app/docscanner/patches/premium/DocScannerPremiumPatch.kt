package app.docscanner.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.docscanner.patches.shared.Constants.COMPATIBILITY_DOCSCANNER

/**
 * Doc Scanner Premium (com.cv.docscanner 6.9.14)
 *
 * Forces the two central entitlement gates in obfuscated class n78
 * (classes3/n78.smali) to return true:
 *
 *  1. IsPremiumFingerprint → n78.b() — master gate (all ads, paywall, pro
 *     features, Firebase pro/free topic).
 *  2. IsSubscribedFingerprint → n78.a() — subscription gate; b() also falls
 *     through to it at :cond_47, and CustomThemeActivity calls it directly, so
 *     both are patched.
 *
 * Both fingerprints now match methods directly (no class anchor): on 6.9.14
 * three classes contain the one-time SKU strings, so the old class anchor
 * resolved to the wrong class and the method filters never ran.
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
        val premium = IsPremiumFingerprint.matchAll()
        if (premium.isEmpty()) {
            throw PatchException(
                "Doc Scanner Premium: the master paid-state gate (public static Z() " +
                    "reading IS_DONATED) was not found. The entitlement model changed."
            )
        }

        // The subscription fingerprint deliberately matches every gate that reads
        // PRE_ACC_KEY and asks the owned-SKU helper; b() is one of them and is
        // already covered by IsPremiumFingerprint above, so de-duplicate by
        // method signature before injecting.
        val targets = (premium + IsSubscribedFingerprint.matchAll())
            .map { it.method }
            .distinctBy { it.toString() }

        targets.forEach { method ->
            method.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """
            )
        }
    }
}

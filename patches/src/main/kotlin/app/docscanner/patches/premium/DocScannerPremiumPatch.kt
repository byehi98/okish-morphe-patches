package app.docscanner.patches.premium

import app.docscanner.patches.shared.Constants.COMPATIBILITY_DOCSCANNER
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Doc Scanner Premium (com.cv.docscanner 6.9.16)
 *
 * Forces every premium entitlement entry gate in the obfuscated gate class
 * (classes3/b88.smali on 6.9.16) to return true:
 *
 *   a()  subscription gate — PRE_ACC_KEY + quarterly/yearly SKU list. Called by
 *        CustomThemeActivity and by billing screens directly.
 *   b()  master gate — `c() || a()`. 42 call sites: every ad surface, paywall,
 *        pro feature and the Firebase pro/free topic selection branch on it.
 *   c()  one-time/donated gate — PRE_ACC_KEY + IS_DONATED + proversion/
 *        large_amount SKU list.
 *   d()  "any subscription SKU owned" helper, used by billing/restore UI and by
 *        f(). Forcing it true keeps those surfaces consistent with the unlocked
 *        state.
 *
 * The class is located through [PremiumGateFingerprint] (the only no-arg public
 * static boolean that reads `IS_DONATED`), so no obfuscated class or method name
 * is hard-coded. Methods are selected by signature, not name: no-arg, boolean
 * return, public static. On 6.9.16 that yields exactly a/b/c/d; the string-based
 * helper e(String) and timestamp helper f()J are excluded by their signatures.
 *
 * Premium state is local SharedPreferences only (no server-side entitlement, no
 * LVL, no purchaseToken validation), so a forced true is permanent. Side effects
 * of the unlock: all AdMob banners removed, pro/custom themes enabled.
 */
@Suppress("unused")
val docScannerPremiumPatch = bytecodePatch(
    name = "Doc Scanner Premium",
    description = "Unlocks all premium features, removes ads, and enables pro themes."
) {
    compatibleWith(COMPATIBILITY_DOCSCANNER)

    execute {
        val gateClass = PremiumGateFingerprint.match().classDef

        val gates = gateClass.methods.filter { method ->
            method.returnType == "Z" &&
                method.parameterTypes.isEmpty() &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                AccessFlags.STATIC.isSet(method.accessFlags)
        }

        if (gates.size < 2) {
            throw PatchException(
                "Doc Scanner Premium: expected the gate class to expose several no-arg " +
                    "public static boolean gates, found ${gates.size} in ${gateClass.type}. " +
                    "The entitlement model changed."
            )
        }

        gates.forEach { method ->
            val registers = method.implementation?.registerCount ?: 0
            if (registers < 1) {
                throw PatchException(
                    "Doc Scanner Premium: ${gateClass.type}->${method.name} has no registers; " +
                        "cannot inject an early true return."
                )
            }

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

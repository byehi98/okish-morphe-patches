package app.smashit.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.smashit.patches.shared.Constants.COMPATIBILITY_SMASHIT

@Suppress("unused")
val smashItPremiumPatch = bytecodePatch(
    name = "Smash Hit Premium Unlock",
    description = "Unlocks premium and all game modes without purchase.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SMASHIT)

    execute {
        // 1. AndroidStore.ownsPremiumProduct() → always return true.
        //    Top-level Java-side premium check.
        OwnsPremiumProductFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // 2. CommandThreadsafeModel.isProductOwned(String) → always return true.
        //    Called by the "isproductowned" command handler when C++ engine queries
        //    whether a product ID is in the owned set.
        //    Declared-synchronized: inject at index 1 (after monitor-enter)
        //    and properly exit the monitor before returning.
        IsProductOwnedFingerprint.method.addInstructions(1, """
            const/4 p1, 0x1
            monitor-exit p0
            return p1
        """.trimIndent())

        // 3. "hasrefreshedownedproducts" handler → always return "true".
        //    The C++ engine polls this flag before it will ever call "isproductowned".
        //    Without this, the engine waits indefinitely for a purchase sync that
        //    never completes on a patched app, so isProductOwned is never reached.
        HasRefreshedOwnedProductsFingerprint.method.addInstructions(0, """
            const-string p1, "true"
            return-object p1
        """.trimIndent())
    }
}

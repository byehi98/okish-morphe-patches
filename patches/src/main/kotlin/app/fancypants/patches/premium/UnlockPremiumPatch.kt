package app.fancypants.patches.premium

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.fancypants.patches.shared.Constants.COMPATIBILITY_FANCYPANTS

/**
 * Forces OTTBilling.isPurchased to always return true.
 *
 * isPurchased is the single source-of-truth entitlement check that
 * MainActivity.updatePurchases() keys all four unlock fields off:
 *   fancy_pants_premium  -> mIsPremium
 *   fancy_pants_wardrobe -> mWardrobeUnlocked
 *   fancy_pants_world1   -> mWorld1Unlocked
 *   fancy_pants_world2   -> mWorld2Unlocked
 * Forcing true therefore unlocks premium, wardrobe, world 1 and world 2 in a
 * single patch, and (because the age gate only launches when !isPremiumUnlocked())
 * implicitly clears the age gate.
 */
@Suppress("unused")
val fancyPantsUnlockPremiumPatch = bytecodePatch(
    name = "Fancy Pants Unlock Premium",
    description = "Unlocks premium, wardrobe, world 1 and world 2 by forcing OTTBilling.isPurchased to always return true.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FANCYPANTS)

    execute {
        IsPurchasedFingerprint.method.returnEarly(true)
    }
}

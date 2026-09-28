package app.lumina.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.lumina.patches.shared.Constants.COMPATIBILITY_LUMINA

/**
 * Lumina Wallpapers — premium unlock (client-side gating only, verified 1.0.2.6).
 *
 * The whole entitlement model lives on-device: one central predicate
 * (`id.b.c()Z` — prefs "lifetime" OR `User.isUpgraded() && userId != "0"`) is
 * consulted by all 11 paywall/click-gate sites, and the model getters
 * `Wallpaper.isPremium` / `Category.isPremium` only drive click toasts and lock
 * overlays — no list is ever filtered by them.
 *
 * Four hooks, all constant-return at offset 0 (register counts verified in
 * notes `premium-bypass.md`: `.registers 4 / 2 / 2 / 2`):
 *  1. Lifetime check → `true`: the primary gate — one override covers every
 *     paywall, purchase dialog and "locked" flag.
 *  2. Wallpaper.isPremium → `false`: wallpapers read as free (click-gates open,
 *     lock overlays never draw).
 *  3. Category.isPremium → `false`: categories read as free (no paywall dialog,
 *     no premium badge).
 *  4. Category.isPurchased → `true`: belt-and-braces — all 3 callers treat
 *     `true` as purchased (smali-verified per-caller in Fingerprints.kt); keeps
 *     unlock badges / locked-flags consistent independently of hooks 1 and 3.
 *
 * PairIP license bypass lives separately in `patches/license/`.
 * Ads are NOT covered here: `HomeActivity.w` reads `"lifetime"`/`"premium"`
 * raw from SharedPreferences, so premium does not imply ad-free (notes §7b).
 *
 * `default = true` — repo convention for unlock patches (all existing premium
 * patches in this repo ship enabled by default).
 */
@Suppress("unused")
val luminaPremiumPatch = bytecodePatch(
    name = "Lumina Premium",
    description = "Unlocks all premium wallpapers and categories.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LUMINA)

    execute {
        // 1. Central lifetime gate — every premium decision consults this.
        LifetimePremiumCheckFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // 2. Wallpapers read as free content (.registers 2, v0 free).
        WallpaperIsPremiumFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // 3. Categories read as free content (.registers 2, v0 free).
        CategoryIsPremiumFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // 4. Categories read as already purchased — true = purchased/unlocked
        //    at all 3 call sites (.registers 2, v0 free).
        CategoryIsPurchasedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())
    }
}

package app.opheliasparadise.patches.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.hexPatch
import app.opheliasparadise.patches.pairip.pairipBypassPatch
import app.opheliasparadise.patches.shared.Constants.COMPATIBILITY_OPHELIAS_PARADISE

/**
 * Ophelia's Paradise — permanent "full version" unlock.
 *
 * `PremiumUpgrade.HasPremiumUpgrade()` is a purely offline gate:
 *
 *     return PlayerPrefs.GetInt("full_version_upgrade", 0) == 1;
 *
 * and `PremiumUpgrade.PurchasePremium()` only does
 * `PlayerPrefs.SetInt("full_version_upgrade", 1)` + `Save()` + hides the ad popup
 * GameObject. There is no receipt validation, no server call and no signature check
 * anywhere in the C# assembly — so forcing the predicate to `true` is the entire
 * unlock.
 *
 * Only three call sites consume it (all verified in the arm64 disassembly):
 *   LifeManager.OnEnable+0x10                          — hides the hearts/life UI
 *   CircleRotator.<ShowAndSelectUiElements>d__20+0x238 — unlocks character/gem selection
 *   MainMenuButtons.Play+0x3c                          — skips the "out of lives" ad popup
 *
 * Site (arm64, on-disk offset = vaddr - 0x4000):
 *   vaddr 0x29B1F24 → file 0x29ADF24, function start   (26.11.15 — was 0x29B1E1C / 0x29ADE1C)
 *
 * Verify window (16 bytes, unique in libil2cpp.so):
 *   FE 0F 1E F8 F4 4F 01 A9 13 A6 01 B0 D4 88 01 90
 *     str  x30, [sp, #-0x20]!
 *     stp  x20, x19, [sp, #0x10]
 *     adrp x19, 0x5e72000
 *     adrp x20, 0x5ac9000   (prologue of the original body)
 *
 * Replacement (same length — the hex patch library zero-pads shorter replacements,
 * so the windows must match):
 *   20 00 80 52   mov w0, #1
 *   C0 03 5F D6   ret
 *   1F 20 03 D5   nop
 *   1F 20 03 D5   nop
 *
 * The skipped class-initialisation in the original prologue is only needed by the
 * body we removed, so returning early is safe.
 */
@Suppress("unused")
val premiumUnlockPatch = rawResourcePatch(
    name = "Unlock full version",
    description = "Makes PremiumUpgrade.HasPremiumUpgrade() always return true, unlocking the " +
        "full version: no lives/energy system, unlocked character and gem selection, and no " +
        "out-of-lives ad popup. The check is a local PlayerPrefs flag with no server-side " +
        "validation, so the entitlement persists.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_OPHELIAS_PARADISE)

    // PairIP force-exits any re-signed build before the game code runs.
    dependsOn(pairipBypassPatch)

    dependsOn(hexPatch(block = {
        // PremiumUpgrade.HasPremiumUpgrade() @ vaddr 0x29B1F24 (file 0x29ADF24)
        "FE 0F 1E F8 F4 4F 01 A9 13 A6 01 B0 D4 88 01 90" asPatternTo
            "20 00 80 52 C0 03 5F D6 1F 20 03 D5 1F 20 03 D5" inFile
            "lib/arm64-v8a/libil2cpp.so"
    }))
}

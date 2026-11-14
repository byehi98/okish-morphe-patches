package app.fancade.patches.iap

import app.fancade.patches.shared.Constants.COMPATIBILITY_FANCADE
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Fancade 1.15.26 (versionCode 190) — Free IAP, native in-place hex patch.
 *
 * WHY NATIVE: purchases are driven entirely by the custom C++ engine
 * (`libfancade.so`). The Java side (`com.martinmagni.fancade.Fancade`) only forwards
 * to Play Billing; the grant itself (gems + no-ad time) happens in `iap_purchased`.
 * On a re-signed install Play Billing never connects, so the shop rows stay greyed
 * out and the purchase callback never fires. Rewriting the native decision points
 * removes Billing from the loop.
 *
 * WHY `hexPatch` AND NOT `replaceWith`: each library is ~40-45 MB. Embedding it in
 * the `.mpp` would bloat the patch bundle by that amount for a handful of bytes.
 * `hexPatch` edits the `.so` in place while the APK is being built and throws a
 * `PatchException` when an anchor is missing, so a stale anchor fails the build
 * instead of producing an APK whose native patches silently do nothing.
 *
 * BOTH ABIs: since 1.15.26 the XAPK ships `armeabi-v7a` as well as `arm64-v8a`, so
 * every site is patched twice — once with AArch64 encodings in
 * `lib/arm64-v8a/libfancade.so`, once with Thumb encodings in
 * `lib/armeabi-v7a/libfancade.so`. `ignoreMissingTargetFiles = true` makes the
 * arm64/v7a halves independent: an APK that ships only one ABI still patches
 * cleanly instead of failing on the absent library. Inside a present library a
 * missing anchor still aborts the build.
 *
 * Neither binary is stripped, so all sites are re-derivable by name:
 *   nm -D libfancade.so | rg 'menu_iap_can_buy|iap_is_ready|iap_purchase|iap_purchased'
 *
 * 1.15.26 sites (file offset == vaddr in both binaries; the executable LOAD
 * segment starts at offset 0):
 *
 *   | symbol                  | arm64-v8a  | armeabi-v7a (Thumb) |
 *   |-------------------------|------------|---------------------|
 *   | menu_iap_can_buy        | 0x3a342c   | 0x29f6d4            |
 *   | iap_is_ready            | 0x2d7160   | 0x1f162c            |
 *   | iap_purchase            | 0x2d701c   | 0x1f156c            |
 *   | iap_purchased           | 0x2d704c   | 0x1f1594            |
 *
 * (armeabi-v7a values are the even function addresses; `nm -D` sets bit 0 because
 * the functions are Thumb.)
 *
 * SITES:
 *
 *  1. `menu_iap_can_buy` — returns whether the shop's gold rows are tappable.
 *     Force `mov w0, #1 ; ret` (`movs r0, #1 ; bx lr` on v7a) so the rows are
 *     enabled when Billing never connects.
 *
 *  2. `iap_is_ready` — readiness used by `select_tab` to decide whether to kick
 *     off `iap_fetch()` retries and by the "waiting for Play Store" UI. Force
 *     `mov w0, #1 ; ret` so no retry storm runs and the UI agrees the store is
 *     usable.
 *
 *  3. `iap_purchase(int idx)` — the wrapper the menu calls with the product index
 *     already in `w0`/`r0`. Replace its entry with an unconditional branch to
 *     `iap_purchased`, the real grant routine.
 *       - arm64: `b 0x2d704c` → `imm26 = (0x2d704c - 0x2d701c) >> 2 = 0xC`,
 *         word `0x1400000C` → LE `0C 00 00 14`.
 *       - v7a (Thumb-2 B.W, PC = addr + 4): `offset = 0x1f1594 - 0x1f1570 = 0x24`,
 *         `S=0, I1=0, I2=0, imm10=0, imm11=0x12` →
 *         halfwords `0xF000`, `0x9000 | (J1<<13) | (J2<<11) | 0x12 = 0xB812`
 *         → LE bytes `00 F0 12 B8`.
 *     `iap_purchased(int idx, double price, String token)` never checks price or
 *     token and has no idempotency guard: it adds `gem_table[idx]` (100 / 500 /
 *     1500 / 1500) and extends no-ads by `dur_table[idx]` (30 d / 183 d / 365 d /
 *     ~permanent), then writes both to the local DB and Firebase. So index 3
 *     (`gold4`) = permanent no-ads.
 *
 * The analytics event is skipped because product entry `+0xb3` (arm64) / `+0xa3`
 * (v7a) is never set on this path, so no fake revenue is reported to Adjust.
 *
 * Updating for a new version: `nm -D libfancade.so | rg 'menu_iap_can_buy|
 * iap_is_ready|iap_purchase|iap_purchased'`, dump the first bytes at each address
 * for BOTH ABIs (arm64: 5 words; v7a: 16 bytes), re-check that every anchor is
 * unique, and recompute the two branches if `iap_purchased` moved.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libfancade.so"
private const val LIB_ARM32 = "lib/armeabi-v7a/libfancade.so"

private val freeIapSites: HexPatchBuilder.() -> Unit = {
    // ── arm64-v8a ────────────────────────────────────────────────────────────
    // menu_iap_can_buy @0x3a342c -> mov w0, #1 ; ret
    "88 14 00 90 08 39 42 F9 08 01 40 B9 1F 09 00 71 8B 02 00 54" asPatternTo
        "20 00 80 52 C0 03 5F D6 08 01 40 B9 1F 09 00 71 8B 02 00 54" inFile LIB_ARM64

    // iap_is_ready @0x2d7160 -> mov w0, #1 ; ret
    "C8 1A 00 F0 08 69 46 F9 08 01 40 B9 1F 05 00 71 0B 01 00 54" asPatternTo
        "20 00 80 52 C0 03 5F D6 08 01 40 B9 1F 05 00 71 0B 01 00 54" inFile LIB_ARM64

    // iap_purchase @0x2d701c -> b iap_purchased (@0x2d704c); w0 still holds the index
    "C8 1A 00 F0 E9 1A 00 90 0A 17 80 52 08 6D 46 F9 29 15 40 F9" asPatternTo
        "0C 00 00 14 E9 1A 00 90 0A 17 80 52 08 6D 46 F9 29 15 40 F9" inFile LIB_ARM64

    // ── armeabi-v7a (Thumb) ──────────────────────────────────────────────────
    // menu_iap_can_buy @0x29f6d4 -> movs r0, #1 ; bx lr
    "80 B5 6F 46 11 48 78 44 00 68 00 68 02 28 BC BF" asPatternTo
        "01 20 70 47 11 48 78 44 00 68 00 68 02 28 BC BF" inFile LIB_ARM32

    // iap_is_ready @0x1f162c -> movs r0, #1 ; bx lr
    "08 48 78 44 00 68 00 68 01 28 BC BF" asPatternTo
        "01 20 70 47 00 68 00 68 01 28 BC BF" inFile LIB_ARM32

    // iap_purchase @0x1f156c -> b.w iap_purchased (@0x1f1594); r0 still holds the index
    "07 49 A8 23 07 4A 79 44 7A 44 09 68 12 68 09 68" asPatternTo
        "00 F0 12 B8 07 4A 79 44 7A 44 09 68 12 68 09 68" inFile LIB_ARM32
}

@Suppress("unused")
val fancadeFreeIapPatch = rawResourcePatch(
    name = "Free IAP",
    description = "Buy any gold pack without paying: each pack grants its gems and its " +
        "no-ads time (the last pack is permanent), and purchases persist after a restart. " +
        "No Play payment dialog is shown.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FANCADE)

    dependsOn(
        hexPatch(
            ignoreMissingTargetFiles = true,
            block = freeIapSites
        )
    )
}

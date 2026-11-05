package app.fancade.patches.iap

import app.fancade.patches.shared.Constants.COMPATIBILITY_FANCADE
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Fancade 1.15.24 (versionCode 184) — Free IAP, native in-place hex patch.
 *
 * WHY NATIVE: purchases are driven entirely by the custom C++ engine
 * (`libfancade.so`, 45,921,576 B, arm64-v8a only). The Java side
 * (`com.martinmagni.fancade.Fancade`) only forwards to Play Billing; the grant itself
 * (gems + no-ad time) happens in `iap_purchased_hook`. On a re-signed install Play
 * Billing never connects, so the shop rows stay greyed out and the purchase callback
 * never fires. Rewriting the native decision points removes Billing from the loop.
 *
 * WHY `hexPatch` AND NOT `replaceWith`: the library is 45 MB. Embedding it in the
 * `.mpp` would bloat the patch bundle by that amount for a handful of bytes. `hexPatch`
 * edits `lib/arm64-v8a/libfancade.so` in place while the APK is being built and throws a
 * `PatchException` when an anchor is missing, so a stale anchor fails the build instead
 * of producing an APK whose native patches silently do nothing.
 *
 * OFFSETS: the binary is **not stripped** — the symbols below are exported (`nm -D`), so
 * they can be re-derived on a future version even if the bytes move. For this build the
 * executable LOAD segment starts at offset 0 / vaddr 0, so the addresses below are both
 * virtual addresses and file offsets. All three 20/12-byte anchors were byte-scanned
 * against the shipped `.so` (sha256 d5613f58…405ac328) and occur **exactly once**.
 *
 * SITES (arm64, file offset == vaddr):
 *
 *  1. `menu_iap_can_buy` @0x3af474 — returns whether the shop's gold rows are tappable.
 *     Force `mov w0, #1 ; ret` so the rows are enabled when Billing never connects.
 *     The trailing `ldr/cmp/b.lo` is kept verbatim (replacement is the same length).
 *
 *  2. `iap_is_ready` @0x2e33e0 — readiness used by `select_tab` to decide whether to
 *     kick off `iap_fetch()` retries and by the "waiting for Play Store" UI. Force
 *     `mov w0, #1 ; ret` so no retry storm runs and the rest of the UI agrees that the
 *     store is usable.
 *
 *  3. `iap_purchase(int idx)` @0x2e329c — the JNI wrapper the menu tail-calls with the
 *     product index already in `w0`. Replace its first instruction with `b +0x30`
 *     (`0C 00 00 14`) so it jumps straight to `iap_purchased` @0x2e32cc, the real grant
 *     routine. `iap_purchased_hook` never checks price or token and has no idempotency
 *     guard: it adds `gem_table[idx]` (100 / 500 / 1500 / 1500) and extends no-ads by
 *     `dur_table[idx]` (30 d / 183 d / 365 d / 1e12 s ≈ permanent), then writes both to
 *     the local DB and Firebase. So index 3 (`gold4`) = permanent no-ads.
 *
 * The analytics event is skipped because product entry `+0xb3` is never set on this
 * path, so no fake revenue is reported to Adjust.
 *
 * Updating for a new version: `nm -D libfancade.so | rg 'menu_iap_can_buy|iap_is_ready|
 * iap_purchase|iap_purchased'`, dump the first bytes at each address, re-check that the
 * anchor is unique, and recompute the branch if `iap_purchase` moved
 * (`imm26 = (target - pc) >> 2`, opcode `0x14000000`). See
 * `analysis/fancade/possible-patches.md` §3 and §9.
 */
private const val LIB = "lib/arm64-v8a/libfancade.so"

private val freeIapSites: HexPatchBuilder.() -> Unit = {
    // menu_iap_can_buy @0x3af474 -> mov w0, #1 ; ret
    "C8 14 00 B0 08 A1 41 F9 08 01 40 B9 1F 09 00 71 8B 02 00 54" asPatternTo
    "20 00 80 52 C0 03 5F D6 08 01 40 B9 1F 09 00 71 8B 02 00 54" inFile LIB

    // iap_is_ready @0x2e33e0 -> mov w0, #1 ; ret
    "28 1B 00 90 08 D1 45 F9 08 01 40 B9 1F 05 00 71 0B 01 00 54" asPatternTo
    "20 00 80 52 C0 03 5F D6 08 01 40 B9 1F 05 00 71 0B 01 00 54" inFile LIB

    // iap_purchase @0x2e329c -> b iap_purchased (@0x2e32cc); w0 still holds the product index
    "28 1B 00 90 29 1B 00 90 0A 17 80 52" asPatternTo
    "0C 00 00 14 29 1B 00 90 0A 17 80 52" inFile LIB
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

    dependsOn(hexPatch(block = freeIapSites))
}

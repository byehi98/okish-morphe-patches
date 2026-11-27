package app.dantheaman.patches.billing

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Dan the Man — native IAP grant (libmortargame.so, arm64-v8a + armeabi-v7a, 1.14.14).
 *
 * WHY: the store's real GRANT gate is `GameStoreService::Update` polling the
 * active purchase object with `ChankaNet::GetLastPurchaseResult` (wrapping
 * `IAP_Support`'s result var — `[0]` = result code, `[4]` = result string):
 *
 *     result == 0  -> GRANT (inventory +1, UpdatePremium, success event)
 *     result  > 0  -> keep waiting
 *     result  < 0  -> CANCEL (BRICKNET_PURCHASE_CANCELLED, purchase ptr cleared)
 *
 * Java cannot write that var — the only registered JNI natives are
 * `ConsumedResponse` and `DetailsCallback`, which go through the pending-consume
 * ring, never the result var. So `IAP_Support::DoPurchase` must BIRTH the result
 * as 0: it initializes `lastPurchaseResult` with `1` (= PENDING forever when
 * offline, which is why the Java-side fake DtmFreeIapPatch handshake alone still
 * shows "purchasing failed" on some installs). Flipping that single immediate to
 * 0 makes every purchase born "successful" → the store grants on the next Update
 * tick (~16 ms, before any Firebase validation response could land). A later -1
 * from failed cloud validation is harmless (purchase pointer already cleared).
 *
 * Both shipped ABIs contain the same logic and are patched, so the APK is
 * correct on 64-bit and 32-bit devices:
 *
 *   ABI          DoPurchase (symbol)  result var   patched instruction
 *   arm64-v8a    0x6947b4             0x1124208    `mov w8, #1` -> `mov w8, #0`
 *   armeabi-v7a  0x5489d4 (Thumb)     0xC7D106     `movs r0, #1` -> `movs r0, #0`
 *
 * 1.14.14 note: both functions kept the same instruction/register layout; the
 * symbol table moved by ~0x11E0 (arm64) / ~0x180 (armv7), so every
 * PC-relative immediate in the anchor run changed (adrp/add of the SKU rodata
 * pointer + both BL/BLX offsets). The arm64 anchor also starts at a different
 * adrp x1 encoding (`41 49 00 90` / `21 FC 19 91`); the armv7 anchor differs
 * only in the trailing BLX immediate (`45 F7 52 E8`). All values below were
 * re-read from the live 1.14.14 binaries and re-verified UNIQUE (count = 1
 * each, already-patched variant count = 0).
 *
 * Addresses above were resolved from the exported symbol table
 * (`_ZN11IAP_Support10DoPurchaseEPKc`) — the library is not stripped — and the
 * mutation point sits immediately before the `str` that publishes the value
 * (`str w8, [x21]` / `str r0, [r6]`), so no register is clobbered in between.
 *
 * Patterns are the 48-byte (arm64) / 32-byte (thumb) instruction run around that
 * store, verified UNIQUE in each 1.14.12 library (occurrences = 1 each). Only the
 * single immediate byte differs in the replacement. Everything else in the run —
 * including PC-relative ADRP/ADD and BL/BLX targets — is carried over verbatim
 * from the live binaries, so the anchor doubles as a byte-for-byte diff check.
 *
 * `hexPatch(true)` skips files that are absent from the APK, so the same patch
 * also works on single-ABI bundles (arm64-only or armeabi-v7a-only splits);
 * a *present* library whose bytes no longer match still fails the build loudly
 * instead of silently patching nothing.
 *
 * Standalone: does NOT depend on DtmFreeIapPatch (which stays as-is); alone it
 * grants purchases against the real Play flow.
 */

private const val LIB_ARM64 = "lib/arm64-v8a/libmortargame.so"
private const val LIB_ARMV7 = "lib/armeabi-v7a/libmortargame.so"

@Suppress("unused")
val dtmNativeIapPatch = rawResourcePatch(
    name = "Native IAP Patch",
    description = "Fixes the native purchase result on both ABIs (arm64-v8a + armeabi-v7a) " +
        "so every store purchase (gold packs, skins, remove ads) is granted on device — " +
        "without depending on the Java bridge handshake.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DTM)

    // First (positional) flag = "skip files that are not present in the APK".
    dependsOn(hexPatch(true, block = {
        // ── arm64-v8a: IAP_Support::DoPurchase @ 0x6947b4 ────────────────────
        // mov w8, #1  @ 0x6947dc  ->  mov w8, #0   (payload byte 12)
        "41 49 00 90 F3 03 00 AA B5 22 08 91 28 00 80 52" +
            "21 FC 19 91 E0 03 14 AA E2 03 1F AA A8 02 00 B9" +
            "94 5C FD 97 E0 03 13 AA BF 06 00 B9 45 58 FD 97" asPatternTo
        "41 49 00 90 F3 03 00 AA B5 22 08 91 08 00 80 52" +
            "21 FC 19 91 E0 03 14 AA E2 03 1F AA A8 02 00 B9" +
            "94 5C FD 97 E0 03 13 AA BF 06 00 B9 45 58 FD 97" inFile LIB_ARM64

        // ── armeabi-v7a: IAP_Support::DoPurchase @ 0x5489d4 (Thumb-2) ────────
        // movs r0, #1  @ 0x5489e2  ->  movs r0, #0   (payload byte 6: 01 -> 00)
        "10 4E 04 46 10 49 01 20 10 4D 7E 44 79 44 00 22" +
            "7D 44 30 60 05 F1 1C 00 4F F0 00 08 45 F7 52 E8" asPatternTo
        "10 4E 04 46 10 49 00 20 10 4D 7E 44 79 44 00 22" +
            "7D 44 30 60 05 F1 1C 00 4F F0 00 08 45 F7 52 E8" inFile LIB_ARMV7
    }))
}

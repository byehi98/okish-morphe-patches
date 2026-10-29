package app.dantheaman.patches.billing

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Dan the Man — native IAP grant (libmortargame.so, arm64-v8a, 1.14.10).
 *
 * WHY: the store's real GRANT gate is `GameStoreService::Update`
 * (0x9c4560) polling the active purchase object at `[svc+0xc8]` with
 * `ChankaNet::GetLastPurchaseResult` (wrapping `IAP_Support`'s result var
 * at 0x1128208):
 *
 *     cbz w0 -> 0 == GRANT  (inventory +1, UpdatePremium, success event)
 *     tbz w0, #0x1f -> > 0 == keep waiting
 *     w0 < 0 == CANCEL (BRICKNET_PURCHASE_CANCELLED, purchase ptr cleared)
 *
 * Java cannot write that var — the only registered JNI natives are
 * `ConsumedResponse` and `DetailsCallback` (RegisterNativeFunctions
 * 0x6938d4), which go through the pending-consume ring, never the result
 * var. So `IAP_Support::DoPurchase` (0x6950cc) must BIRTH the result as 0:
 * it initializes `lastPurchaseResult` with `mov w8, #0x1` (0x6950f4 =
 * PENDING forever offline, which is exactly why the Java-side fake
 * DtmFreeIapPatch handshake alone still shows "purchasing failed" on some
 * installs). Changing that single instruction to `mov w8, #0x0`
 * (`28 00 80 52` → `08 00 80 52` at payload byte 12) makes every purchase
 * born "successful" → the store grants on the next Update tick (~16 ms,
 * before any Firebase validation response could land). A later -1 from
 * failed cloud validation is harmless (purchase pointer already cleared).
 *
 * The pattern below is the 11-instruction run 0x6950e8..0x695114 in
 * IAP_Support::DoPurchase, verified UNIQUE in libmortargame.so
 * (occurrences = 1; .text vaddr == file offset, readelf confirmed).
 * Verified in `analysis/dantheaman/notes/iap-native-redirect.md`.
 * hexPatch throws when the pattern is missing, so an app update that
 * changes these bytes fails the build instead of silently patching
 * nothing.
 *
 * Standalone: does NOT depend on DtmFreeIapPatch (which stays as-is);
 * alone it grants purchases against the real Play flow. Its occurrence-
 * count-1 anchor is the byte-for-byte diff check (`28` → `08` only).
 */

private const val LIB = "lib/arm64-v8a/libmortargame.so"

@Suppress("unused")
val dtmNativeIapPatch = rawResourcePatch(
    name = "Native IAP Patch",
    description = "Fixes the native purchase result so every store purchase (gold packs, " +
        "skins, remove ads) is granted on device — without depending on the Java " +
        "bridge handshake.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DTM)

    dependsOn(hexPatch(block = {
        // IAP_Support::DoPurchase @ 0x6950f4: mov w8, #0x1 -> mov w8, #0x0
        "41 49 00 B0 F3 03 00 AA B5 22 08 91 28 00 80 52" +
            "21 BC 29 91 E0 03 14 AA E2 03 1F AA A8 02 00 B9" +
            "BE 58 FD 97 E0 03 13 AA BF 06 00 B9" asPatternTo
        "41 49 00 B0 F3 03 00 AA B5 22 08 91 08 00 80 52" +
            "21 BC 29 91 E0 03 14 AA E2 03 1F AA A8 02 00 B9" +
            "BE 58 FD 97 E0 03 13 AA BF 06 00 B9" inFile LIB
    }))
}

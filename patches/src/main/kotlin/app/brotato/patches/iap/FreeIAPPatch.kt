package app.brotato.patches.iap

import app.brotato.patches.pairip.pairipBypassPatch
import app.brotato.patches.shared.Constants.COMPATIBILITY_BROTATO
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * **Brotato 1.3.951 — free IAP (shop purchases).** Every real-money tile in the shop, the
 * DLC shop and the mall is granted locally: no Play billing, no charge, no receipt
 * round-trip.
 *
 * Unity IL2CPP title (`com.brotato.shooting.survivors.action.roguelike`), shipped as an
 * APKM with one `libil2cpp.so` per ABI, so every site is patched per ABI:
 *
 * | ABI | library | RVA → file offset |
 * |---|---|---|
 * | arm64-v8a | `lib/arm64-v8a/libil2cpp.so` | `file = RVA` (single RX LOAD at vaddr 0) |
 * | armeabi-v7a | `lib/armeabi-v7a/libil2cpp.so` | `file = RVA` (A32/ARM, not Thumb) |
 * | x86_64 | `lib/x86_64/libil2cpp.so` | `file = RVA` (single RX LOAD at vaddr 0) |
 *
 * `x86` (32-bit, `split_config.x86`) is **not** patched — real devices are ARM and modern
 * emulators pick x86_64 first. On an x86-only install the patch is a no-op.
 *
 * ### The real-money flow (verified in the arm64 + armv7 disassembly)
 *
 * `UIShopData.Buy()` switches on `ShopData.ePriceType`:
 *
 * | `ePriceType` | handler |
 * |---|---|
 * | `0` (money) | `UIShopData.Purchase()` |
 * | `1`, `2` | `UIShopData.OperateAD()` |
 * | `60001` | `UIShopData.ExchangePotato()` |
 * | `14465`, `15464` | `UIShopData.ExchangeEventCoin()` |
 * | `60004` | `UIShopData.ExchangeGem()` |
 * | hero-shop / frag | `ExchangeGoods()` / `ExchangeCouponAD()` |
 *
 * `Purchase()` has exactly **one** `BL` caller in the whole library (`Buy()`), and it calls
 * `IAPManager.Purchase(...)` (itself with exactly one `BL` caller) with
 * `<Purchase>b__0(string orderId)` as the success callback — that callback is just
 * `UIShopData.PurchaseSuccess(orderId)`. Every shop button — `BuyBtn.Buy()` (0x2F0A9B4),
 * `DlcShopUI.Buy()` (0xE33030), mall tiles — funnels through `UIShopData.Buy()`.
 *
 * `PurchaseSuccess(orderId)` is a purely local grant:
 *
 * ```
 * if (!DBStyle.IsOrderFinish(orderId)) {   // list of finished order ids in the save
 *     DBStyle.SaveOrder(orderId);          // append (idempotent, null/empty ignored)
 *     ...isPurchased = true...
 *     BuySuccess(null);                    // -> AddReward() -> GoodsModel.AddGoods,
 *                                          //    AdModel.noAd, DLC unlocks, ...
 *     SDKEventManager.SendPayment / Tenjin / GA   // analytics only
 * }
 * ```
 *
 * `BuySuccess` touches only `DBStyle`, `GoodsModel`, `AdModel`, `HeroModel`,
 * `GoodsShopModel` and `TaskModel` — no network call anywhere in the grant.
 *
 * ### Site 1 — `UIShopData.Purchase()` entry
 *
 * `Purchase()` is rewritten to skip `IAPManager` and go straight to the game's own success
 * handler with the tile's product id as the order id:
 *
 * ```
 * arm64                          armv7                  x86_64
 * stp x19, x30, [sp, #-0x10]!    push {r4, lr}          push rbx
 * mov x19, x0                    mov  r4, r0            mov rbx, rdi
 * bl  UIShopData.get_PurchaseKey (same)                 call UIShopData.get_PurchaseKey
 * mov x1, x0                     mov  r1, r0            mov rsi, rax   ; orderId
 * mov x0, x19                    mov  r0, r4            mov rdi, rbx   ; this
 * ldp x19, x30, [sp], #0x10      pop  {r4, lr}          pop rbx
 * b   UIShopData.PurchaseSuccess (same)                 jmp UIShopData.PurchaseSuccess
 * ```
 *
 * The frame is balanced before the tail branch, so `PurchaseSuccess` returns directly to
 * `Buy()` (`Purchase()` is `void`). `get_PurchaseKey()` returns the platform product id
 * (`ShopData` field +0x38 on Android); `DBStyle.SaveOrder` only appends a string, it does
 * not parse the id, so the product id is a valid order key.
 *
 * | ABI | RVA / file offset | anchor | replacement |
 * |---|---|---|---|
 * | arm64 | `0xF534F8` | 32 B | 32 B |
 * | armv7 | `0x9FFC58` | 32 B | 32 B |
 * | x86_64 | `0xD79BB8` | 32 B | 32 B |
 *
 * ### Site 2 — drop the "order already finished" gate in `PurchaseSuccess()`
 *
 * `PurchaseSuccess` starts with `if (DBStyle.IsOrderFinish(orderId)) return;`. Real Play
 * orders are unique per purchase, but our synthetic order id is the product id, so without
 * this the second purchase of the same tile (consumable gem/fund packs) would be silently
 * dropped. The branch is replaced by a `nop`, so every tap grants; `SaveOrder` stays
 * idempotent.
 *
 * | ABI | RVA / file offset | anchor | replacement |
 * |---|---|---|---|
 * | arm64 | `0xF548A8` (inside `PurchaseSuccess` `0xF547D8`) | 24 B | 24 B |
 * | armv7 | `0xA01CE0` (inside `PurchaseSuccess` `0xA01BF8`) | 16 B | 16 B |
 * | x86_64 | `0xD7AED8` (inside `PurchaseSuccess` `0xD7AE0F`) | 20 B | 20 B |
 *
 * Every anchor occurs exactly once per library (checked by byte search), and every
 * replacement is the same length as its anchor. `ignoreMissingTargetFiles` keeps a bundle
 * that ships only one ABI split from failing on the other path.
 *
 * ### Known limits
 *
 * - Client-side only. Anything that is reconciled against Sauce / Play on the server (or a
 *   real receipt) is outside this patch's reach; the grants themselves are local.
 * - Restore (`IAPManager.Restore` → `RestoreFinish` → `PurchaseSuccess`) still works but is
 *   irrelevant on a build without Play billing.
 * - `_validateReceipt` / `ValidateReceipt` are never reached, because `IAPManager.Purchase`
 *   is no longer called.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libil2cpp.so"
private const val LIB_ARM32 = "lib/armeabi-v7a/libil2cpp.so"
private const val LIB_X64 = "lib/x86_64/libil2cpp.so"

private val freeIapSites: HexPatchBuilder.() -> Unit = {
    // ── arm64-v8a ─────────────────────────────────────────────────────────────
    // 1. UIShopData.Purchase() @ 0xF534F8: purchaseKey -> this -> PurchaseSuccess.
    "FF 83 01 D1 FB 6B 01 A9 F9 63 02 A9 F7 5B 03 A9 F5 53 04 A9 F3 7B 05 A9 74 9C 01 90 B5 86 01 D0" asPatternTo
        "F3 7B BF A9 F3 03 00 AA 04 FC FF 97 E1 03 00 AA E0 03 13 AA F3 7B C1 A8 B2 04 00 14 B5 86 01 D0" inFile LIB_ARM64

    // 2. UIShopData.PurchaseSuccess() @ 0xF548A8: nop the IsOrderFinish early return.
    "E2 03 1F AA 02 B4 0F 94 00 07 00 37 68 87 01 D0 08 4D 43 F9 E1 03 14 AA" asPatternTo
        "E2 03 1F AA 02 B4 0F 94 1F 20 03 D5 68 87 01 D0 08 4D 43 F9 E1 03 14 AA" inFile LIB_ARM64

    // ── armeabi-v7a (A32 / ARM, not Thumb; file offset == RVA) ─────────────────
    // 1. UIShopData.Purchase() @ 0x9FFC58: purchaseKey -> this -> PurchaseSuccess.
    "F0 4F 2D E9 14 D0 4D E2 58 54 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 2E 00 00 1A" asPatternTo
        "10 40 2D E9 00 40 A0 E1 B2 F5 FF EB 00 10 A0 E1 04 00 A0 E1 10 40 BD E8 E0 07 00 EA 2E 00 00 1A" inFile LIB_ARM32

    // 2. UIShopData.PurchaseSuccess() @ 0xA01CE0: nop the IsOrderFinish early return.
    "00 00 50 E3 41 00 00 1A 2C 01 9F E5 05 10 A0 E1" asPatternTo
        "00 00 50 E3 00 F0 20 E3 2C 01 9F E5 05 10 A0 E1" inFile LIB_ARM32

    // ── x86_64 (file offset == RVA) ───────────────────────────────────────────
    // 1. UIShopData.Purchase() @ 0xD79BB8: purchaseKey -> this -> PurchaseSuccess.
    "55 41 57 41 56 41 55 41 54 53 48 83 EC 18 49 89 FE 80 3D 1C ED 50 03 00 0F 85 BB 00 00 00 48 8D" asPatternTo
        "53 48 89 FB E8 04 F1 FF FF 48 89 C6 48 89 DF 5B E9 42 12 00 00 50 03 00 0F 85 BB 00 00 00 48 8D" inFile LIB_X64

    // 2. UIShopData.PurchaseSuccess() @ 0xD7AED8: nop the IsOrderFinish early return.
    "4C 89 FE 31 D2 E8 F4 89 3E 00 84 C0 0F 85 D7 00 00 00 48 8D" asPatternTo
        "4C 89 FE 31 D2 E8 F4 89 3E 00 84 C0 90 90 90 90 90 90 48 8D" inFile LIB_X64
}

@Suppress("unused")
val freeIapPatch = rawResourcePatch(
    name = "Free IAP",
    description = "Every real-money shop, mall and DLC tile is granted locally the moment you " +
        "tap it — no Play billing, no payment screen, no charge. Repeat purchases of the same " +
        "tile keep working. Client-side only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_BROTATO)

    // PairIP force-exits any re-signed build before the game code runs.
    dependsOn(pairipBypassPatch)

    dependsOn(hexPatch(ignoreMissingTargetFiles = true, block = freeIapSites))
}

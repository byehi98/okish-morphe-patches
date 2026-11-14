package app.smashbandits.patches.billing

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch
import app.smashbandits.patches.shared.Constants.COMPATIBILITY_SMASH_BANDITS

/**
 * Smash Bandits Racing 1.10.05.6 · **Free Store Purchases** — every real-money product is
 * granted instantly and for free. No Google Play purchase sheet is shown and nothing is
 * charged.
 *
 * Unity 2018.4.32f1 IL2CPP title (`com.hutchgames.smashbandits`). The whole shop, wallet and
 * billing logic lives in `lib/arm64-v8a/libil2cpp.so`; the DEX only contains Unity/Prime31/
 * Facebook/billing glue, so this is a static native patch: two byte ranges are rewritten in
 * the library while the APK is built. Nothing is loaded at runtime, and the edited bytes
 * survive every launch.
 *
 * ### Mechanism — delegate replay
 *
 * The purchase path is `UIStore* → GameManager.StartPurchaseStoreProduct` → coroutine
 * `<TryToStartPurchaseStoreProduct>d__501.MoveNext` (one frame later) →
 * `HutchStore.StartPurchaseStoreProduct`. The UI never calls the `HutchStore` method
 * directly, so overwriting its **entry** intercepts every route
 * (`UIStoreProductEntry.OnClick`, `UICarSelectEntry.OnBuyCarConfirmButton`,
 * `UIStoreRemoveAds.OnClick`, `ProcessMessageAction`).
 *
 * Instead of faking a purchase result inside the platform strategy, the stub replays the
 * game's own `PurchaseComplete` delegate (`HutchStore` field `+0x50`,
 * `Action<string,bool>`). That multicast delegate is already wired to
 * `CurrencyPurchaseComplete` (adds chips/bucks), `BundlePurchaseComplete` (grants bundles and
 * cars) and `GameManager.OnPurchaseComplete` (UI refresh, closes the store overlay), so the
 * real grant code runs unchanged. Because the grant path is the game's own,
 * `CurrencyPurchaseComplete` also never consumes the product, so repeat purchases keep
 * working.
 *
 * ### Target 1a — `HutchStore.StartPurchaseStoreProduct` entry (`file 0x96BD84`)
 *
 * The function is 192 bytes long; the first 92 are replaced with an 84-byte stub plus two
 * `nop`s, and the remainder becomes unreachable dead code. RVA equals the file offset in
 * this library.
 *
 * ```
 * stp  x29, x30, [sp, #-0x20]!   ; frame
 * mov  x29, sp
 * stp  x19, x20, [sp, #0x10]
 * mov  x19, x0                   ; this      (callee-saved)
 * mov  x20, x1                   ; productId (callee-saved)
 * adrp x8, #0x1B8F000            ; resolve the RGCTX MethodInfo for
 * ldr  x8, [x8, #0xE90]          ;  Action<string,bool>.Invoke exactly as
 * mov  x0, x8                    ;  TransactionValidationSucceeded+0x30 and
 * bl   #0x4E5CB4                 ;  UnityIAPStrategy.OnProcessPurchase+0x28 do
 * adrp x8, #0x1B8F000            ;
 * ldr  x8, [x8, #0xE90]          ;
 * ldr  x3, [x8]                  ; x3 = MethodInfo*
 * ldr  x0, [x19, #0x50]          ; PurchaseComplete delegate
 * cbz  x0, done                  ; null-safe
 * mov  x1, x20                   ; productId
 * mov  w2, #1                    ; bIsActualSale = true
 * bl   #0x904C10                 ; Action<string,bool>.Invoke
 * done:
 * mov  w0, #1                    ; report success
 * ldp  x19, x20, [sp, #0x10]
 * ldp  x29, x30, [sp], #0x20
 * ret
 * nop
 * nop
 * ```
 *
 * `bl 0x4E5CB4` is the `il2cpp_codegen_initialize_runtime_metadata` thunk. The resolver does
 * an `ldaxr`/`stlxr` guard that returns immediately once the slot is resolved, so calling it
 * on every purchase is safe (and necessary the first time, because the RGCTX usage cell at
 * `0x1BEBCE8` still holds an encoded token until then). The `BL` immediates are PC-relative,
 * so the stub must stay at `0x96BD84`.
 *
 * ### Target 1b — `HutchStore.get_IsStoreSetup` entry (`file 0x96B048`)
 *
 * The opening block is replaced with `mov w0, #1; ret` followed by `nop` padding. This makes
 * the store UI treat the store as ready even when Google Play Services is absent, so tiles
 * remain tappable. Callers include `GameManager.ShowMiniStore+0x80`,
 * `GameManager.StartPurchaseStoreProduct+0xAC`, `UIFrontEndRoot.ShowShopScreen+0x7C` and
 * `UIShopFrontEndGrid.TryToSetupDisplay+0x98`. The rest of the original method is dead code.
 *
 * ### Caveats
 *
 * * **`PurchaseStart` / `IsWaitingForPurchase` are not set.** `PurchaseStart` (field `+0x48`)
 *   and the waiting flag are never touched, because the real strategy method is bypassed. No
 *   caller in this build depends on them for correctness — the completion event is what
 *   closes the store overlay.
 * * **Prices may render blank.** The strategy never queried the Play catalogue, so tiles can
 *   show an empty or "—" price string. Cosmetic only; tapping still grants the product.
 * * **Google Play is never contacted**, so the patch works on devices without Play Services
 *   and leaves no Play transaction or receipt.
 * * **Analytics.** The stub passes `bIsActualSale = true`, matching what the UI shows. To
 *   keep the game's own `_SpentIAP` / `paidUsdAndroid` counters at zero, change the stub's
 *   `mov w2, #1` (`22 00 80 52`) to `mov w2, #0` (`02 00 80 52`); grants are unaffected
 *   (`CurrencyPurchaseComplete` never reads that flag).
 * * **arm64 only.** The APK also ships `lib/armeabi-v7a/libil2cpp.so`; these patterns are for
 *   arm64, so a 32-bit-only device runs the game unpatched.
 * * **Version safety.** Both anchors occur exactly once in the 1.10.05.6 `libil2cpp.so` and
 *   each replacement is the same length as its pattern. `hexPatch` throws
 *   (`Pattern not found in target file`) when an anchor stops matching, so a future build
 *   fails at patch time instead of shipping a half-patched library.
 *
 * OBB note: the base APK is only ~41 MB; the ~238 MB asset OBB (or first-run download) is
 * still required and is not affected by this patch.
 */
private const val LIB = "lib/arm64-v8a/libil2cpp.so"

// HutchStore.StartPurchaseStoreProduct entry @ file 0x96BD84 (92 bytes).
private const val START_PURCHASE_PATTERN =
    "F5 0F 1D F8 F4 4F 01 A9 FD 7B 02 A9 FD 83 00 91 75 9A 00 B0 A8 8A 54 39 " +
        "F3 03 01 AA F4 03 00 AA C8 00 00 37 00 91 00 D0 00 78 41 F9 C1 E7 ED 97 " +
        "E8 03 00 32 A8 8A 14 39 94 56 40 F9 14 04 00 B4 0A 91 00 D0 88 02 40 F9 " +
        "4A 79 41 F9 09 55 42 79 41 01 40 F9 69 01 00 B4 0B 59 40 F9"

private const val START_PURCHASE_REPLACEMENT =
    "FD 7B BE A9 FD 03 00 91 F3 53 01 A9 F3 03 00 AA F4 03 01 AA 28 91 00 90 " +
        "08 49 47 F9 E0 03 08 AA C4 E7 ED 97 28 91 00 90 08 49 47 F9 03 01 40 F9 " +
        "60 2A 40 F9 80 00 00 B4 E1 03 14 AA 22 00 80 52 93 63 FE 97 20 00 80 52 " +
        "F3 53 41 A9 FD 7B C2 A8 C0 03 5F D6 1F 20 03 D5 1F 20 03 D5"

// HutchStore.get_IsStoreSetup entry @ file 0x96B048 (32 bytes).
private const val STORE_SETUP_PATTERN =
    "F4 4F BE A9 FD 7B 01 A9 FD 43 00 91 74 9A 00 B0 88 7A 54 39 F3 03 00 AA " +
        "C8 00 00 37 00 91 00 D0"

private const val STORE_SETUP_REPLACEMENT =
    "20 00 80 52 C0 03 5F D6 1F 20 03 D5 1F 20 03 D5 1F 20 03 D5 1F 20 03 D5 " +
        "1F 20 03 D5 1F 20 03 D5"

private val storeSites: HexPatchBuilder.() -> Unit = {
    // HutchStore.StartPurchaseStoreProduct: replay the PurchaseComplete delegate
    // (CurrencyPurchaseComplete + BundlePurchaseComplete + GameManager.OnPurchaseComplete).
    START_PURCHASE_PATTERN asPatternTo
    START_PURCHASE_REPLACEMENT inFile LIB

    // HutchStore.get_IsStoreSetup: always report the store as set up.
    STORE_SETUP_PATTERN asPatternTo
    STORE_SETUP_REPLACEMENT inFile LIB
}

@Suppress("unused")
val freeStorePurchasesPatch = rawResourcePatch(
    name = "Free Store Purchases",
    description = "Everything in the shop is free. Tap a Bandit Chips pack, a Smash Bucks " +
        "pack, a starter bundle or Remove Ads and the content is granted immediately — no " +
        "Play purchase sheet, no charge.\n" +
        "\n" +
        "⚠ Requires the game assets (OBB). This APK does not contain the ~238 MB asset " +
        "bundle, so the game stays on the loading screen until it is installed: extract " +
        "main.1100506.com.hutchgames.smashbandits.obb from the original APKM/XAPK and copy " +
        "it to /sdcard/Android/obb/com.hutchgames.smashbandits/ (internal storage, not the " +
        "SD card).",
    default = true
) {
    compatibleWith(COMPATIBILITY_SMASH_BANDITS)

    dependsOn(hexPatch(block = storeSites))
}

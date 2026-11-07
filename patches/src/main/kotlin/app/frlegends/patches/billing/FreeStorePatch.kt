package app.frlegends.patches.billing

import app.frlegends.patches.shared.Constants.COMPATIBILITY_FR_LEGENDS
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * FR Legends 0.4.9 · **Free Store Purchases** — every shop product is granted without
 * contacting Google Play.
 *
 * Unity 2022.3 IL2CPP title (`com.fengiiley.frlegends`, arm64-v8a only). Game logic is
 * entirely in `lib/arm64-v8a/libil2cpp.so`, so this is a static native patch: two byte
 * ranges are rewritten inside the library while the APK is being built. The DEX is never
 * touched, nothing is loaded at runtime, and the edited bytes survive every launch.
 *
 * ### Target 1 — `Purchaser.Buy` entry (`file 0x147ADC4`, RVA `0x147EDC4`)
 *
 * `void Buy(string productId)` (instance method: `x0 = this`, `x1 = pid`) is the single
 * funnel behind every store tile. It is *not* called with `BL` from anywhere — the UI binds
 * it as a `UnityAction` delegate — but overwriting the method *entry* still intercepts every
 * invocation, because the delegate holds this exact code address. `BuyConsumable` is a
 * one-instruction tail branch (`b 0x147EDC4`) into the same body and is therefore covered
 * for free.
 *
 * The 32-byte stub replaces the whole prologue and calls the game's own grant chain:
 *
 * ```
 * stp  x30, x19, [sp, #-0x20]!
 * mov  x19, x1                  ; keep the caller's pid (callee-saved)
 * bl   ProcessPurchase(pid)     ; 0x147DEF8 — GetContent + ProcessContent + success dialog
 * bl   Purchaser.get_Instance   ; 0x147DCDC
 * mov  x1, xzr                  ; Order = null
 * bl   OnPurchaseConfirmed      ; 0x14806A0 — Debug.Log + PurchaseCallback.Invoke()
 * ldp  x30, x19, [sp], #0x20
 * ret
 * ```
 *
 * `ProcessPurchase` is the real grant engine: it looks the pid up in the 12 hardcoded SKUs
 * (`cash30k`, `cash82k`, `cash420k`, `cash3m`, `gold60`, `gold165`, `gold830`, `gold6k`,
 * `pack18`, `pack96`, `hertfc`, `lcp`) and applies coins, gems, carport slots, cars, or
 * `liveryCreatorPass` exactly as a genuine purchase would. `OnPurchaseConfirmed` ignores
 * its `Order` argument (it only dereferences `PurchaseCallback`), so passing `null` is safe,
 * and it is what closes the store's "please wait" overlay.
 *
 * The `BL` immediates are PC-relative: the stub must stay at `0x147EDC4`. The remainder of
 * the original `Buy` body becomes unreachable dead code inside the same function.
 *
 * ### Target 2 — purchase analytics (`file 0x1479F30`, RVA `0x147DF30`)
 *
 * Inside `ProcessPurchase`, `mov w2, #1` loads `recordUSD = true` for
 * `ProcessContent(pc, recordUSD)`. Rewriting it to `mov w2, #0` keeps `paidUsdAndroid` /
 * `paidUsdIos` at zero and skips the fake revenue bookkeeping. This does not affect what is
 * granted — only the analytics counters.
 *
 * ### Verification
 *
 * Both byte patterns occur exactly once in the 0.4.9 `libil2cpp.so`, at the stated file
 * offsets, and the two spans do not overlap. `hexPatch` throws if a pattern stops matching,
 * so a future game version fails the build instead of silently shipping an unpatched APK.
 * Offsets are file offsets; the RVA→file delta in this binary is `0x4000`.
 */
private const val LIB = "lib/arm64-v8a/libil2cpp.so"

private val storeSites: HexPatchBuilder.() -> Unit = {
    // Purchaser.Buy entry @ file 0x147ADC4: replace the original prologue with a stub that
    // grants the product through the game's own ProcessPurchase/OnPurchaseConfirmed chain.
    "FE 5F BD A9 F6 57 01 A9 F4 4F 02 A9 95 CB 00 F0 A8 0A 63 39 F4 03 01 AA F3 03 00 AA 48 02 00 37" asPatternTo
    "FE 4F BE A9 F3 03 01 AA 4B FC FF 97 C3 FB FF 97 E1 03 1F AA 32 06 00 94 FE 4F C2 A8 C0 03 5F D6" inFile LIB

    // Purchaser.ProcessPurchase @ file 0x1479F30: recordUSD true -> false (no fake revenue).
    "22 00 80 52 E1 03 00 AA F4 03 00 AA 54 01 00 94" asPatternTo
    "02 00 80 52 E1 03 00 AA F4 03 00 AA 54 01 00 94" inFile LIB
}

@Suppress("unused")
val freeStorePurchasesPatch = rawResourcePatch(
    name = "Free Store Purchases",
    description = "Everything in the shop is free. Tap a cash pack, a gold pack, a car pack or " +
        "the Livery Creator Pass and you get it straight away — no payment screen, no charge.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FR_LEGENDS)

    dependsOn(hexPatch(block = storeSites))
}

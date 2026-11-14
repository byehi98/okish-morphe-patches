package app.sonicdash.patches.billing

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch
import app.sonicdash.patches.shared.Constants.COMPATIBILITY_SONIC_DASH

/**
 * Sonic Dash Run 10.7.0 · **Free Store Purchases** — every real-money product is granted
 * locally, without a Google Play charge and without SEGA's receipt round-trip.
 *
 * Unity IL2CPP title (`com.sega.sonicdash`), shipped as a split `.apks` bundle with both
 * ABIs, so each site is patched twice:
 *
 * | ABI | library | RVA → file offset |
 * |---|---|---|
 * | arm64-v8a | `lib/arm64-v8a/libil2cpp.so` | `file = RVA − 0x4000` |
 * | armeabi-v7a | `lib/armeabi-v7a/libil2cpp.so` | `file = RVA` (single RX LOAD at vaddr 0, A32/ARM) |
 *
 * The store's real-money path is the hand-rolled coroutine
 * `ContentCostMoney.<ApplyCost>d__17.MoveNext`. Its state 0 normally either logs in
 * (receipt verification on) or records a pending purchase (off), and the chain eventually
 * reaches `MakePayment` → `IAPManager.RequestPayment` → real Google Play billing. Forcing
 * `IsReceiptVerificationEnabled` false therefore does **not** make purchases free — it only
 * skips SEGA's server check *after* the player has been charged.
 *
 * ### Site 1 — `ContentCostMoney.CanAffordCost`
 *
 * `mov w0, #1 ; ret` (`20 00 80 52 C0 03 5F D6` / `01 00 A0 E3 1E FF 2F E1`). The method
 * normally requires `IAPManager.IsInitialised` and a resolved product before the buy button
 * enables; forcing it true keeps every real-money tile usable even when billing is not
 * initialised (offline / no Play Store).
 *
 * | ABI | RVA | file offset | anchor / replacement |
 * |---|---|---|---|
 * | arm64 | `0x13CF8BC` | `0x13CB8BC` | 24 B / 24 B |
 * | armv7 | `0xB337E4` | `0xB337E4` | 32 B / 32 B |
 *
 * ### Site 2 — state 0 of `MoveNext` → the game's own grant block
 *
 * 10.7.0 splits the success path differently from 10.6.0. Where the 10.6.0 patch had to
 * *write* a grant stub, 10.7.0 already contains a ready-made block that does exactly the
 * right thing and is only reachable from the state-0 dispatch:
 *
 * ```
 * arm64 0x13D28C8 (file 0x13CE8C8): mov x0, x20 ; bl OnCostAppliedSuccessfully ; mov w0, wzr ; b 0x13D291C
 * armv7 0xB36FDC:                   mov r0, r5  ; bl OnCostAppliedSuccessfully ; b 0xB3702C  (epilogue, r6 = 0)
 * ```
 *
 * So 10.7.0 needs only a redirect, not a stub. State 0 is rewritten in place to mark the
 * coroutine done and branch straight into that block:
 *
 * ```
 * arm64 (20 B anchor → 20 B replacement, 12 B used):
 *   mov  w8, #-1
 *   str  w8, [x19, #0x10]        ; coroutine state = -1 (done)
 *   b    0x13D28C8               ; imm26 0x98 @ 0x13D2668
 *
 * armv7 (24 B anchor → 24 B replacement, 12 B used):
 *   mvn  r1, #0
 *   str  r1, [r4, #8]            ; coroutine state = -1 (done)
 *   b    0xB36FDC                ; imm24 0x88 @ 0xB36DB4, pc+8
 * ```
 *
 * The untouched tail of each anchor stays in place as dead code behind the branch. Nothing
 * is moved, so the `BL` inside the grant block keeps its displacement.
 *
 * | ABI | RVA | file offset |
 * |---|---|---|
 * | arm64 | `0x13D2660` | `0x13CE660` |
 * | armv7 | `0xB36DAC` | `0xB36DAC` |
 *
 * Safety: `ApplyCost` writes `m_applyCostData` (`[this + 0x28]` arm64, `[this + 0x18]`
 * armv7) before the coroutine ever starts, and `OnCostAppliedSuccessfully` only invokes the
 * delegate stored there with `-1` plus analytics, so no `MakePayment` result is required.
 * Both grant-block entry points assume `this` is already in `x20` / `r5` (set before the
 * switch) and return "coroutine done" themselves. On armv7 the dispatch table at `0xB36C50`
 * is left untouched — only the state-0 target code is rewritten — and `r6` (MoveNext's
 * return value) is already `0` before dispatch.
 *
 * ### Verification
 *
 * Every anchor occurs exactly once per library at the stated file offset, and every
 * replacement is the same length as its anchor. `hexPatch` throws if an anchor stops
 * matching; `ignoreMissingTargetFiles = true` keeps single-ABI bundles working, since a
 * bundle that ships only one ABI split has no file at the other library path.
 *
 * ### Version support
 *
 * 10.7.0 only. 10.6.0 is no longer supported: the methods moved, the anchors changed and the
 * success path was re-split, so the 10.6.0 offsets and the old grant stub no longer apply.
 *
 * ### Known limits (test live)
 *
 * - `ContentCostMoney.Purchased()` still reports "not owned", so non-consumable tiles remain
 *   purchasable (and re-grant) — expected for a free store.
 * - Server-side revalidation cannot be defeated by a client patch. If SEGA later reconciles
 *   the account, purchases may be rolled back.
 * - Consumable SKUs follow the same completion callback; if a product shows up but is not
 *   applied, the next place to look is `ContentCostMoney.ConsumeIAP`.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libil2cpp.so"
private const val LIB_ARM32 = "lib/armeabi-v7a/libil2cpp.so"

private val freeStoreSites: HexPatchBuilder.() -> Unit = {
    // ── arm64-v8a ─────────────────────────────────────────────────────────────
    // 1. ContentCostMoney.CanAffordCost @ file 0x13CB8BC: every product is affordable.
    "FF C3 00 D1 FE 57 01 A9 F4 4F 02 A9 75 A6 00 D0 94 90 00 F0 F3 03 00 AA" asPatternTo
    "20 00 80 52 C0 03 5F D6 F4 4F 02 A9 75 A6 00 D0 94 90 00 F0 F3 03 00 AA" inFile LIB_ARM64

    // 2. <ApplyCost>d__17.MoveNext state 0 @ file 0x13CE660: mark the coroutine done and
    //    branch straight into the existing grant block at 0x13D28C8 (+0x268, imm26 0x98).
    "88 90 00 90 09 00 80 12 08 CD 44 F9 69 12 00 B9 00 01 40 F9" asPatternTo
    "08 00 80 12 68 12 00 B9 98 00 00 14 69 12 00 B9 00 01 40 F9" inFile LIB_ARM64

    // ── armeabi-v7a (A32 / ARM, not Thumb; file offset == RVA) ─────────────────
    // 1. ContentCostMoney.CanAffordCost @ 0xB337E4: every product is affordable.
    "F0 48 2D E9 08 D0 4D E2 0C 51 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 07 00 00 1A" asPatternTo
    "01 00 A0 E3 1E FF 2F E1 0C 51 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 07 00 00 1A" inFile LIB_ARM32

    // 2. <ApplyCost>d__17.MoveNext state 0 @ 0xB36DAC: mark the coroutine done and branch
    //    into the existing grant block at 0xB36FDC (+0x230, imm24 0x88, pc+8).
    "90 02 9F E5 00 10 E0 E3 00 00 9F E7 08 10 84 E5 00 00 90 E5 74 20 90 E5" asPatternTo
    "00 10 E0 E3 08 10 84 E5 88 00 00 EA 08 10 84 E5 00 00 90 E5 74 20 90 E5" inFile LIB_ARM32
}

@Suppress("unused")
val freeStorePurchasesPatch = rawResourcePatch(
    name = "Free Store Purchases",
    description = "Everything sold for real money is free. Tap a ring pack, a gem pack, a " +
        "red star ring bundle or a character offer and it is granted straight away — no " +
        "payment screen and no charge.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SONIC_DASH)

    dependsOn(hexPatch(ignoreMissingTargetFiles = true, block = freeStoreSites))
}

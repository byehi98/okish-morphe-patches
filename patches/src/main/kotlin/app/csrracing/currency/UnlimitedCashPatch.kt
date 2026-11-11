package app.csrracing.currency

import app.csrracing.shared.Constants.COMPATIBILITY_CSR
import app.csrracing.shared.Constants.LIBIL2CPP_ARM32
import app.csrracing.shared.Constants.LIBIL2CPP_ARM64
import app.csrracing.shared.LibTarget
import app.csrracing.shared.NativeAnchor
import app.csrracing.shared.applyLibil2cppEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * CSR Racing 5.1.5 — **Unlimited Cash** (static native patch).
 *
 * Unity IL2CPP title: the economy lives in `libil2cpp.so`, not in the R8-minified
 * DEX shell, so this is an anchored in-place byte edit. The APK is fat and ships
 * **both** ABIs, so both are patched; each ABI has its own addresses and encoding.
 *
 * Two disjoint edits per ABI, both in `PlayerProfile`:
 *
 * | Site | arm64 RVA | armv7 RVA | Original | Patched |
 * |---|---|---|---|---|
 * | `GetCurrentCash` | `0x614F18` | `0x2BBB8C` | `int`; derives `CashEarned + Account.Cash − CashSpent`, clamps to `0x7FFFFFFF` | return `0x7FFFFFFF` |
 * | `SpendCash(int)` | `0x60DD08` | `0x2B3EE8` | `void`, same shape as `SpendGold` | return immediately |
 *
 * The arm64 stubs are `mov w0,#0x7FFFFFFF ; ret`
 * (`E0FF9F52E0FFAF72C0035FD6`) and a bare `ret` (`C0035FD6`). The armv7 code is
 * **ARM state, not Thumb**, so the getter stub is
 * `movw r0,#0xFFFF ; movt r0,#0x7FFF ; bx lr` (`FF0F0FE3FF0F47E31EFF2FE1`)
 * and the spend stub is `bx lr` (`1EFF2FE1`).
 *
 * Why this is safe:
 * - **The balance is derived, never stored.** `GetCurrentCash()` is the same
 *   shape as gold with `CashEarned + Account.Cash − CashSpent`, so
 *   short-circuiting the getter changes no stored field at all.
 * - `MemoryValidator` + `IMangledDataItem` guard **only** the four
 *   Earned/Spent counters. A patched getter gives `Validate()` nothing to
 *   compare, and `SpendCash` writing nothing keeps every counter consistent
 *   with its mangle — both edits are invisible to it.
 * - **Never write the raw Earned/Spent fields directly** — that would
 *   desynchronise the mangle and trigger `MangleInvoker.ResetFromMemoryHack`.
 * - `SpendCash` is `void` and touches the counter only *after* the
 *   `amount < 1` guard, so returning at entry is a complete no-op.
 *
 * `0x7FFFFFFF` matches the real method's own clamp, so this is the natural
 * ceiling and the in-game number formatting already handles it.
 *
 * 12 B and 4 B are written per ABI, each inside its own uniquely-verified window
 * (24 B on arm64, 48 B / 56 B on armv7 — see [app.csrracing.shared.applyLibil2cppEdits]),
 * so no other function can be touched.
 */
@Suppress("unused")
val unlimitedCashPatch = rawResourcePatch(
    name = "Unlimited Cash",
    description = "Cash reads as 2,147,483,647 everywhere and cash purchases never deduct it. " +
        "The game's OBB data file (462.6 MiB main.20251202.com.naturalmotion.csrracing.obb) " +
        "must still be installed for the game to boot.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_CSR)

    execute {
        applyLibil2cppEdits(
            patchName = "Unlimited Cash",
            targets = listOf(
                LibTarget(LIBIL2CPP_ARM64, listOf(GET_CURRENT_CASH_A64, SPEND_CASH_A64)),
                LibTarget(LIBIL2CPP_ARM32, listOf(GET_CURRENT_CASH_V7, SPEND_CASH_V7)),
            ),
        )
    }
}

/**
 * `PlayerProfile.GetCurrentCash()` → `0x7FFFFFFF` (arm64).
 *
 * Original 24 B prologue at `0x614F18`:
 * `str x22,[sp,#-0x30]!` (`F6 57 BD A9`), `str x20,[sp,#0x10]` (`F4 4F 01 A9`),
 * `str x29,[sp,#0x20]` (`FD 7B 02 A9`), `add x29,sp,#0x20` (`FD 83 00 91`),
 * then the `ProfileData` field loads (`54 9A 00 D0 88 5E 49 39`).
 * Only the trailing field-load bytes differ from the gold getter, so the 24-byte
 * window is required for a unique match.
 */
private val GET_CURRENT_CASH_A64 = NativeAnchor(
    label = "PlayerProfile.GetCurrentCash -> 0x7FFFFFFF [arm64]",
    rva = 0x614F18,
    fileOffset = 0x614F18,
    verifyHex = "F657BDA9F44F01A9FD7B02A9FD830091549A00D0885E4939",
    replacementHex = "E0FF9F52E0FFAF72C0035FD6", // mov w0, #0x7FFFFFFF ; ret
)

/**
 * `PlayerProfile.SpendCash(int)` → `ret` (arm64).
 *
 * Original 24 B prologue at `0x60DD08`; the body writes `CashSpent` only after
 * the `amount < 1` early-out, so returning on entry skips the mangled write
 * entirely and leaves the derived balance pinned by [GET_CURRENT_CASH_A64].
 */
private val SPEND_CASH_A64 = NativeAnchor(
    label = "PlayerProfile.SpendCash -> ret [arm64]",
    rva = 0x60DD08,
    fileOffset = 0x60DD08,
    verifyHex = "F657BDA9F44F01A9FD7B02A9FD830091959A00B0A8AA4939",
    replacementHex = "C0035FD6", // ret
)

/**
 * `PlayerProfile.GetCurrentCash()` → `0x7FFFFFFF` (armv7, **ARM mode**).
 *
 * Original prologue at `0x2BBB8C`:
 * `push {r4,r5,r6,sl,fp,lr}` (`70 4C 2D E9`), `add fp,sp,#0x10` (`10 B0 8D E2`),
 * `mov r4,r0` (`00 40 A0 E1`), then the IL2CPP metadata-initialisation guard and
 * the `ProfileData` load (`68 01 9F E5 …`).
 *
 * The 24 B armv7 window is **not** unique (it occurs 2× in the lib), so the
 * window is 48 B — still the same function head, just extended past the guard
 * branch so the literal-pool displacements make it unique.
 */
private val GET_CURRENT_CASH_V7 = NativeAnchor(
    label = "PlayerProfile.GetCurrentCash -> 0x7FFFFFFF [armv7]",
    rva = 0x2BBB8C,
    fileOffset = 0x2BBB8C,
    verifyHex = "704C2DE910B08DE20040A0E168019FE500008FE00000D0E5000050E30600001A" +
        "58019FE500009FE7000090E5D81535EB",
    replacementHex = "FF0F0FE3FF0F47E31EFF2FE1", // movw r0, #0xFFFF ; movt r0, #0x7FFF ; bx lr
)

/**
 * `PlayerProfile.SpendCash(int)` → return (armv7, **ARM mode**).
 *
 * Original 56 B head at `0x2B3EE8`; the body writes `CashSpent` only after the
 * `amount < 1` early-out, so `bx lr` on entry skips the mangled write entirely.
 * 56 B is the shortest window that occurs exactly once in the armv7 lib.
 */
private val SPEND_CASH_V7 = NativeAnchor(
    label = "PlayerProfile.SpendCash -> ret [armv7]",
    rva = 0x2B3EE8,
    fileOffset = 0x2B3EE8,
    verifyHex = "704C2DE910B08DE208D04DE20040A0E1B4009FE50150A0E100008FE00000D0E5000050E30600001A" +
        "A0009FE500009FE7000090E5FF3435EB",
    replacementHex = "1EFF2FE1", // bx lr
)

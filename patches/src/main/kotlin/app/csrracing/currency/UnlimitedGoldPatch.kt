package app.csrracing.currency

import app.csrracing.shared.Constants.COMPATIBILITY_CSR
import app.csrracing.shared.Constants.LIBIL2CPP_ARM32
import app.csrracing.shared.Constants.LIBIL2CPP_ARM64
import app.csrracing.shared.LibTarget
import app.csrracing.shared.NativeAnchor
import app.csrracing.shared.applyLibil2cppEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * CSR Racing 5.1.5 — **Unlimited Gold** (static native patch).
 *
 * Unity IL2CPP title: the economy lives in `libil2cpp.so`, not in the R8-minified
 * DEX shell, so this is an anchored in-place byte edit. The APK is fat and ships
 * **both** ABIs, so both are patched; each ABI has its own addresses and encoding.
 *
 * Two disjoint edits per ABI, both in `PlayerProfile`:
 *
 * | Site | arm64 RVA | armv7 RVA | Original | Patched |
 * |---|---|---|---|---|
 * | `GetCurrentGold` | `0x614DE4` | `0x2BBA30` | `int`; derives `GoldEarned + Account.Gold − GoldSpent` | return `0x0FFFFFFF` |
 * | `SpendGold(int)` | `0x60DDB4` | `0x2B3FC4` | `void`; `if (amount < 1) return;` then `GoldSpent += amount` | return immediately |
 *
 * The arm64 stubs are `mov w0,#0x0FFFFFFF ; ret`
 * (`E0FF9F52E0FFA172C0035FD6`) and a bare `ret` (`C0035FD6`). The armv7 code is
 * **ARM state, not Thumb**, so the getter stub is
 * `movw r0,#0xFFFF ; movt r0,#0x0FFF ; bx lr` (`FF0F0FE3FF0F40E31EFF2FE1`)
 * and the spend stub is `bx lr` (`1EFF2FE1`).
 *
 * Why this is safe:
 * - **The balance is derived, never stored.** `GetCurrentGold()` is
 *   `ProfileData.GoldEarned + Account.Gold − ProfileData.GoldSpent`, so
 *   short-circuiting the getter changes no stored field at all.
 * - `MemoryValidator` + `IMangledDataItem` guard **only** the four
 *   Earned/Spent counters. A patched getter gives `Validate()` nothing to
 *   compare, and `SpendGold` writing nothing keeps every counter consistent
 *   with its mangle — both edits are invisible to it.
 * - **Never write the raw Earned/Spent fields directly** — that would
 *   desynchronise the mangle and trigger `MangleInvoker.ResetFromMemoryHack`.
 * - `SpendGold` is `void` and touches the counter only *after* the
 *   `amount < 1` guard, so returning at entry is a complete no-op.
 *
 * `0x0FFFFFFF` (268,435,455) rather than `0x7FFFFFFF` is deliberate: it keeps
 * downstream `balance + delta` arithmetic from overflowing, while still reading
 * as "maxed" in the HUD and making every gold purchase affordable.
 *
 * 12 B and 4 B are written per ABI, each inside its own uniquely-verified window
 * (24 B on arm64, 48 B / 52 B on armv7 — see [app.csrracing.shared.applyLibil2cppEdits]),
 * so no other function can be touched.
 */
@Suppress("unused")
val unlimitedGoldPatch = rawResourcePatch(
    name = "Unlimited Gold",
    description = "Gold reads as 268,435,455 everywhere and gold purchases never deduct it. " +
        "The game's OBB data file (462.6 MiB main.20251202.com.naturalmotion.csrracing.obb) " +
        "must still be installed for the game to boot.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_CSR)

    execute {
        applyLibil2cppEdits(
            patchName = "Unlimited Gold",
            targets = listOf(
                LibTarget(LIBIL2CPP_ARM64, listOf(GET_CURRENT_GOLD_A64, SPEND_GOLD_A64)),
                LibTarget(LIBIL2CPP_ARM32, listOf(GET_CURRENT_GOLD_V7, SPEND_GOLD_V7)),
            ),
        )
    }
}

/**
 * `PlayerProfile.GetCurrentGold()` → `0x0FFFFFFF` (arm64).
 *
 * Original 24 B prologue at `0x614DE4`:
 * `str x22,[sp,#-0x30]!` (`F6 57 BD A9`), `str x20,[sp,#0x10]` (`F4 4F 01 A9`),
 * `str x29,[sp,#0x20]` (`FD 7B 02 A9`), `add x29,sp,#0x20` (`FD 83 00 91`),
 * then the `ProfileData` field loads (`54 9A 00 D0 88 5A 49 39`).
 * The prologue is shared by several `PlayerProfile` getters, which is exactly
 * why the 24-byte window (not just the 4–12 bytes written) proves uniqueness.
 */
private val GET_CURRENT_GOLD_A64 = NativeAnchor(
    label = "PlayerProfile.GetCurrentGold -> 0x0FFFFFFF [arm64]",
    rva = 0x614DE4,
    fileOffset = 0x614DE4,
    verifyHex = "F657BDA9F44F01A9FD7B02A9FD830091549A00D0885A4939",
    replacementHex = "E0FF9F52E0FFA172C0035FD6", // mov w0, #0x0FFFFFFF ; ret
)

/**
 * `PlayerProfile.SpendGold(int)` → `ret` (arm64).
 *
 * Original 24 B prologue at `0x60DDB4`; the body writes `GoldSpent` only after
 * the `amount < 1` early-out, so returning on entry skips the mangled write
 * entirely and leaves the derived balance pinned by [GET_CURRENT_GOLD_A64].
 */
private val SPEND_GOLD_A64 = NativeAnchor(
    label = "PlayerProfile.SpendGold -> ret [arm64]",
    rva = 0x60DDB4,
    fileOffset = 0x60DDB4,
    verifyHex = "F657BDA9F44F01A9FD7B02A9FD830091959A00B0A8A64939",
    replacementHex = "C0035FD6", // ret
)

/**
 * `PlayerProfile.GetCurrentGold()` → `0x0FFFFFFF` (armv7, **ARM mode**).
 *
 * Original prologue at `0x2BBA30`:
 * `push {r4,r5,r6,sl,fp,lr}` (`70 4C 2D E9`), `add fp,sp,#0x10` (`10 B0 8D E2`),
 * `mov r4,r0` (`00 40 A0 E1`), then the IL2CPP metadata-initialisation guard and
 * the `ProfileData` load (`38 01 9F E5 …`).
 *
 * The 24 B armv7 window is **not** unique (it occurs 4× in the lib), so the
 * window is 48 B — still the same function head, just extended past the guard
 * branch so the literal-pool displacements make it unique.
 */
private val GET_CURRENT_GOLD_V7 = NativeAnchor(
    label = "PlayerProfile.GetCurrentGold -> 0x0FFFFFFF [armv7]",
    rva = 0x2BBA30,
    fileOffset = 0x2BBA30,
    verifyHex = "704C2DE910B08DE20040A0E138019FE500008FE00000D0E5000050E30600001A" +
        "28019FE500009FE7000090E52F1635EB",
    replacementHex = "FF0F0FE3FF0F40E31EFF2FE1", // movw r0, #0xFFFF ; movt r0, #0x0FFF ; bx lr
)

/**
 * `PlayerProfile.SpendGold(int)` → return (armv7, **ARM mode**).
 *
 * Original 52 B head at `0x2B3FC4`; the body writes `GoldSpent` only after the
 * `amount < 1` early-out, so `bx lr` on entry skips the mangled write entirely.
 * 52 B is the shortest window that occurs exactly once in the armv7 lib.
 */
private val SPEND_GOLD_V7 = NativeAnchor(
    label = "PlayerProfile.SpendGold -> ret [armv7]",
    rva = 0x2B3FC4,
    fileOffset = 0x2B3FC4,
    verifyHex = "704C2DE910B08DE20040A0E1AC009FE50150A0E100008FE00000D0E5000050E30600001A" +
        "98009FE500009FE7000090E5C93435EB",
    replacementHex = "1EFF2FE1", // bx lr
)

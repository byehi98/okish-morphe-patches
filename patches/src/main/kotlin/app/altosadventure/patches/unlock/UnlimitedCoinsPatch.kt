package app.altosadventure.patches.unlock

import app.altosadventure.patches.shared.AbiEdits
import app.altosadventure.patches.shared.Constants.COMPATIBILITY_ALTOSADVENTURE
import app.altosadventure.patches.shared.NativeAnchor
import app.altosadventure.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Alto's Adventure 1.8.34 — **Unlimited Coins** (static native patch).
 *
 * Unity 6 / IL2CPP title (metadata v39): the coin balance is pure C# compiled into
 * `libil2cpp.so`. There is no DEX/Java code for it and no save file is edited, so the
 * effect survives clearing app data.
 *
 * ============================================================================
 * WHERE THE WALLET ACTUALLY LIVES
 * ============================================================================
 * ```
 * StatsManager (singleton)            // TypeDefIndex 4042
 *     StatProfileNew _statsProfile;   // 0x30 (arm64) / 0x18 (arm32)
 *     StatProfileNew statsProfile { get; set; }
 *
 * StatProfileNew                      // TypeDefIndex 4080, [Serializable]
 *     public int coins;               // 0x10 (arm64) / 0x08 (arm32)
 * ```
 * `coins` is a **public field**, not a property, so every read is an inlined
 * `ldr wX,[profile,#coins]` — there is no getter to patch (confirmed: the trivial
 * `get_statsProfile` has zero `bl` callers, while `set_statsProfile` has five). The
 * legacy `StatProfile.get_coins()` (TypeDefIndex 11072) is a different, unused class;
 * patching it alone changes nothing visible, which is exactly why the first attempt
 * showed no effect.
 *
 * ============================================================================
 * HOOK — `StatsManager.set_statsProfile(StatProfileNew)`
 * ============================================================================
 * Every profile hand-off goes through this setter, including the startup path:
 * `SaveManager.LoadProfile()` does `bl StatsManager.set_statsProfile` with
 * `x0 = StatsManager.Instance`, `x1 = loadedProfile` (arm64 RVA `0x17313D4`). The
 * setter is *not* inlined (it invokes the `StatsProfileChanged` delegate), so it has
 * real, reachable call sites.
 *
 * We replace its first instruction with a `b` into a trampoline that stamps the coin
 * field and then replays the displaced instruction:
 *
 * ```
 * set_statsProfile:
 *     b cave                       ; replaces `str x30,[sp,#-0x20]!`
 *
 * cave:
 *     cbz  x1, .Lorig              ; null guard — never dereference a null profile
 *     movz w8, #0xC9FF
 *     movk w8, #0x3B9A, lsl #16    ; w8 = 999,999,999
 *     str  w8, [x1, #0x10]         ; value.coins = 999,999,999   (0x8 on arm32)
 * .Lorig:
 *     str  x30, [sp, #-0x20]!      ; the displaced original instruction
 *     b    set_statsProfile+4      ; resume the untouched original body
 * ```
 * The frame layout, `x19`/`x20` saves, the `_statsProfile` store and the delegate
 * invocation all run exactly as stock — only one field is written on the way in.
 *
 * ============================================================================
 * THE CAVE IS GUARANTEED-DEAD CODE WE OWN
 * ============================================================================
 * The cave sits inside `StatProfile.get_coins()` (452 B on arm64, 560 B on arm32), at
 * `+0x40`. That method is stubbed at its entry to return a constant (first edit), so
 * its body can never execute — no separate `.so`, no code cave hunting in padding, no
 * relocation of anything else. `StatProfile.get_coins` itself is a legacy/XML class
 * that no runtime code calls (`bl`-scan: 0 callers), so the stub is inert too.
 *
 * ============================================================================
 * RVA → FILE OFFSET (from the ELF program headers of these exact splits)
 * ============================================================================
 * ```
 * arm64-v8a   LOAD(2) off 0x13dee70  vaddr 0x13e2e70  R E   => fileOffset = RVA - 0x4000
 * armeabi-v7a LOAD(1) off 0x00000000 vaddr 0x00000000 R E   => fileOffset = RVA
 * ```
 * The armv7 lib is **A32, not Thumb** (`push {r4,r5,r11,lr}` = `E92D4830`), so no
 * low-bit Thumb convention applies. RVAs come from an Il2CppDumper build with v39
 * metadata support (`roytu/Il2CppDumper@v39`) run against the same
 * `global-metadata.dat` for both ABIs; every byte was then re-verified against the
 * shipping libs and every branch target was confirmed by disassembling the patched
 * copies.
 *
 * ============================================================================
 * GUARDING — uniqueness is proven, not assumed
 * ============================================================================
 * IL2CPP prologues are extremely repetitive, so each edit carries the shortest verify
 * window that occurs **exactly once in the whole 57.8 MB / 48.8 MB lib** (24–40 B,
 * measured). [applyNativeEdits] re-runs that whole-file count at patch time and
 * refuses to write unless it is exactly 1, so a changed build aborts with zero bytes
 * written instead of corrupting the library.
 */
private const val COINS = "E03F99524073A772C0035FD6" // movz/movk w0, 999999999 ; ret

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private const val STUB_ARM64 = COINS

private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "StatProfile.get_coins() constant stub (makes the cave below dead code)",
        rva = 0x2B37994,
        fileOffset = 0x2B33994,
        verifyHex = "FE57BEA9F44F01A9F45F00F0554F00B0F30300AA88EA7339",
        replacementHex = STUB_ARM64,
    ),
    NativeAnchor(
        label = "coin trampoline (inside the stubbed get_coins body)",
        rva = 0x2B379D4,
        fileOffset = 0x2B339D4,
        verifyHex = "2FCBA9972800805288EA3339A80240F9085D40F9080140F9680200B5484F00B0",
        // cbz x1,+0x10 ; movz w8,#0xC9FF ; movk w8,#0x3B9A,lsl#16 ; str w8,[x1,#0x10]
        // ; str x30,[sp,#-0x20]! ; b set_statsProfile+4
        replacementHex = "810000B4E83F99524873A772281000B9FE0F1EF81F03B017",
    ),
    NativeAnchor(
        label = "StatsManager.set_statsProfile() -> coin trampoline",
        rva = 0x1738660,
        fileOffset = 0x1734660,
        verifyHex = "FE0F1EF8F44F01A9F40300AAF30301AA810E03F8E00314AAF1C7F99788825FF8",
        // b 0x2B379D4
        replacementHex = "DDFC4F14",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private const val STUB_ARM32 = "FF090CE39A0B43E31EFF2FE1" // movw/movt r0, 999999999 ; bx lr

private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "StatProfile.get_coins() constant stub (makes the cave below dead code)",
        rva = 0x22B5AB4,
        fileOffset = 0x22B5AB4,
        // The first 24 B of this prologue are shared with several other accessors
        // (5 hits); 40 B is the shortest unique window.
        verifyHex = "70402DE9CC509FE50040A0E105508FE00000D5E5000050E30A00001AB8009FE500009FE7683F9FEB",
        replacementHex = STUB_ARM32,
    ),
    NativeAnchor(
        label = "coin trampoline (inside the stubbed get_coins body)",
        rva = 0x22B5AF4,
        fileOffset = 0x22B5AF4,
        verifyHex = "0100A0E30000C5E598609FE506609FE7000096E55C0090E5005090E5000055E3",
        // cmp r1,#0 ; beq .Lorig ; movw r8,#0xC9FF ; movt r8,#0x3B9A ; str r8,[r1,#0x8]
        // ; push {r4,r5,r11,lr} ; b set_statsProfile+4
        replacementHex = "000051E30200000AFF890CE39A8B43E3088081E530482DE94A90A5EA",
    ),
    NativeAnchor(
        label = "StatsManager.set_statsProfile() -> coin trampoline",
        rva = 0xC19C38,
        fileOffset = 0xC19C38,
        verifyHex = "30482DE90050A0E10140A0E11810A5E50500A0E1F4AEF9EB040015E5000050E3",
        // b 0x22B5AF4
        replacementHex = "AD6F5AEA",
    ),
)

@Suppress("unused")
val unlimitedCoinsPatch = rawResourcePatch(
    name = "Unlimited Coins",
    description = "Sets your coin balance to 999,999,999 every time your profile loads or updates. " +
        "Coins effectively never run out, so every character and upgrade is affordable. " +
        "Applied to libil2cpp.so at the code level, so clearing app data does not reset it.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALTOSADVENTURE)

    execute {
        applyNativeEdits(
            patchName = "Alto's Adventure unlimited coins",
            targets = listOf(
                AbiEdits("arm64-v8a", ARM64_ANCHORS),
                AbiEdits("armeabi-v7a", ARM32_ANCHORS),
            ),
        )
        println(
            "Alto's Adventure unlimited coins: StatsManager.set_statsProfile() now stamps " +
                "999,999,999 into StatProfileNew.coins on every shipped ABI " +
                "(static in-place edit, no launch code).",
        )
    }
}

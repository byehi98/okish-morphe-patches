package app.altosodyssey.patches.unlock

import app.altosodyssey.patches.shared.AbiEdits
import app.altosodyssey.patches.shared.Constants.COMPATIBILITY_ALTOSODYSSEY
import app.altosodyssey.patches.shared.NativeAnchor
import app.altosodyssey.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Alto's Odyssey 1.0.45 — **Unlimited Coins** (static native patch).
 *
 * Unity 6 / IL2CPP title (metadata v39): the coin balance is pure C# compiled into
 * `libil2cpp.so`. There is no DEX/Java code for it and no save file is edited, so the
 * effect survives clearing app data.
 *
 * ============================================================================
 * WHERE THE WALLET ACTUALLY LIVES
 * ============================================================================
 * ```
 * StatsManager (singleton)            // TypeDefIndex 2404
 *     StatProfile _statsProfile;      // 0x30 (arm64) / 0x18 (arm32)
 *     StatProfile statsProfile { get; set; }
 *
 * StatProfile                         // TypeDefIndex 2446
 *     public int coins;               // 0x10 (arm64) / 0x08 (arm32)
 * ```
 * `coins` is a **public field**, not a property, so every read is an inlined
 * `ldr wX,[profile,#coins]` — there is no getter to patch. The legacy
 * `SerializableClasses.StatProfile.get_coins()` (TypeDefIndex 11526) is a different,
 * unused class; `bl`-scanning both shipping libs finds **0 callers** of it.
 *
 * ============================================================================
 * HOOK — `StatsManager.set_statsProfile(StatProfile)`
 * ============================================================================
 * Every profile hand-off goes through this setter (5 direct `bl` callers per ABI,
 * including the startup/load path). It is *not* inlined because it invokes the
 * `StatsProfileChanged` delegate.
 *
 * We replace its first instruction with a `b` into a trampoline that stamps the coin
 * field and then replays the displaced instruction:
 *
 * ```
 * set_statsProfile:
 *     b cave                       ; replaces `str x30,[sp,#-0x20]!`  (arm32: `push {r4,r5,r11,lr}`)
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
 * The cave sits inside the legacy `SerializableClasses.StatProfile.get_coins()`
 * (452 B on arm64, 560 B on arm32), at `+0x40`. That method is stubbed at its entry
 * to return a constant (first edit), so its body can never execute — no separate
 * `.so`, no padding hunt, no relocation of anything else.
 *
 * ============================================================================
 * RVA → FILE OFFSET (from the ELF program headers of these exact splits)
 * ============================================================================
 * ```
 * arm64-v8a   LOAD(2) off 0x14bc970 vaddr 0x14c0970 R E => fileOffset = RVA - 0x4000
 * armeabi-v7a LOAD(1) off 0x00000000 vaddr 0x00000000 R E => fileOffset = RVA
 * ```
 * The armv7 lib is **A32, not Thumb** (`push {r4,r5,r11,lr}` = `E92D4830`), so no
 * low-bit Thumb convention applies. RVAs come from Il2CppDumper (metadata v39
 * fork) run against this exact `global-metadata.dat` for both ABIs; every byte was
 * re-verified against the shipping libs and every branch target was confirmed by
 * decoding the encoded `b`.
 *
 * ============================================================================
 * GUARDING — uniqueness is proven, not assumed
 * ============================================================================
 * IL2CPP prologues are extremely repetitive, so each edit carries the shortest verify
 * window that occurs **exactly once in the whole 60.4 MB / 51.0 MB lib** (16–40 B,
 * measured). [applyNativeEdits] re-runs that whole-file count at patch time and
 * refuses to write unless it is exactly 1, so a changed build aborts with zero bytes
 * written instead of corrupting the library.
 */
private const val COINS = "E03F99524073A772C0035FD6" // movz/movk w0, 999999999 ; ret

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private const val STUB_ARM64 = COINS

private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "legacy StatProfile.get_coins() constant stub (makes the cave below dead code)",
        rva = 0x2D42E70,
        fileOffset = 0x2D3EE70,
        verifyHex = "FE57BEA9F44F01A9346300B0955100F0F30300AA88C64739",
        replacementHex = STUB_ARM64,
    ),
    NativeAnchor(
        label = "coin trampoline (inside the stubbed get_coins body)",
        rva = 0x2D42EB0,
        fileOffset = 0x2D3EEB0,
        verifyHex = "4F25A5972800805288C60739A80240F9085D40F9080140F9",
        // cbz x1,+0x10 ; movz w8,#0xC9FF ; movk w8,#0x3B9A,lsl#16 ; str w8,[x1,#0x10]
        // ; str x30,[sp,#-0x20]! ; b set_statsProfile+4
        replacementHex = "810000B4E83F99524873A772281000B9FE0F1EF8CDEAAD17",
    ),
    NativeAnchor(
        label = "StatsManager.set_statsProfile() -> coin trampoline",
        rva = 0x18BD9F4,
        fileOffset = 0x18B99F4,
        verifyHex = "FE0F1EF8F44F01A9F40300AAF30301AA810E03F8",
        // b 0x2D42EB0
        replacementHex = "2F155214",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private const val STUB_ARM32 = "FF090CE39A0B43E31EFF2FE1" // movw/movt r0, 999999999 ; bx lr

private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "legacy StatProfile.get_coins() constant stub (makes the cave below dead code)",
        rva = 0x247806C,
        fileOffset = 0x247806C,
        // The first 24 B of this prologue are shared with several other accessors;
        // 40 B (including the unique `bl` displacement) is the shortest unique window.
        verifyHex = "70402DE9CC509FE50040A0E105508FE00000D5E5000050E30A00001AB8009FE500009FE75EB499EB",
        replacementHex = STUB_ARM32,
    ),
    NativeAnchor(
        label = "coin trampoline (inside the stubbed get_coins body)",
        rva = 0x24780AC,
        fileOffset = 0x24780AC,
        verifyHex = "0100A0E30000C5E598609FE506609FE7000096E55C0090E5005090E5",
        // cmp r1,#0 ; beq .Lorig ; movw r8,#0xC9FF ; movt r8,#0x3B9A ; str r8,[r1,#0x8]
        // ; push {r4,r5,r11,lr} ; b set_statsProfile+4
        replacementHex = "000051E30200000AFF890CE39A8B43E3088081E530482DE9201EA3EA",
    ),
    NativeAnchor(
        label = "StatsManager.set_statsProfile() -> coin trampoline",
        rva = 0xD3F948,
        fileOffset = 0xD3F948,
        verifyHex = "30482DE90050A0E10140A0E11810A5E5",
        // b 0x24780AC
        replacementHex = "D7E15CEA",
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
    compatibleWith(COMPATIBILITY_ALTOSODYSSEY)

    execute {
        applyNativeEdits(
            patchName = "Alto's Odyssey unlimited coins",
            targets = listOf(
                AbiEdits("arm64-v8a", ARM64_ANCHORS),
                AbiEdits("armeabi-v7a", ARM32_ANCHORS),
            ),
        )
        println(
            "Alto's Odyssey unlimited coins: StatsManager.set_statsProfile() now stamps " +
                "999,999,999 into StatProfile.coins on every shipped ABI " +
                "(static in-place edit, no launch code).",
        )
    }
}

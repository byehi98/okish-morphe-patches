package app.deadtarget.patches.unlock

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.186.0 — Unlimited ALL in-game currency
 * (arm64-v8a + armeabi-v7a).
 *
 * ============================================================================
 * DUAL-ABI SUPPORT (4.186.0 ships in two shapes)
 * ============================================================================
 * APKPure publishes this version as an **armeabi-v7a-only** XAPK (base +
 * `UnityDataAssetPack.apk` + `config.armeabi_v7a.apk`, libil2cpp.so = 76,435,716 bytes of
 * 32-bit **A32/ARM**), while other re-split mirrors (e.g. `apkcube`, and what `apkeep`
 * currently downloads) include **both** config splits, so the merged APK also contains
 * `lib/arm64-v8a/libil2cpp.so` (85,202,576 bytes of **AArch64**).
 *
 * The old code hard-coded `get("lib/armeabi-v7a/libil2cpp.so", true)` and read it without
 * checking `exists()`, which was the `FileNotFoundException` seen in CI on arm64-only
 * merged APKs. The patch now probes each ABI, patches every library that is actually
 * present, and aborts with a clear message only when neither exists.
 *
 * Addressing: for armeabi-v7a `RVA == FILE OFFSET`; for arm64-v8a
 * `file offset = VA - 0x4000` (exec LOAD segment delta). Anchors are matched by content.
 *
 * ============================================================================
 * SITES — five DSystem getters re-derived for both architectures
 * ============================================================================
 * All five currencies are `DSystem` instance getters, so the value comes back in
 * `r0` (and `r1` for the 64-bit ones) on ARM32, or `x0` (zero-extended for the 32-bit
 * values) on AArch64. Forcing the GETTER — and only the getter — is the whole trick: every
 * affordability check and every UI read in the game goes through these properties, so they
 * all pass, while `set_*` methods still run normally on spend, so purchases, shops and the
 * save file keep working.
 *
 *  | Currency    | Method                          | arm32 RVA    | arm64 RVA    |
 *  |-------------|---------------------------------|--------------|--------------|
 *  | Cash        | DProject.DSystem.get_gameMoney  | 0x013296D8   | 0x021AB024   |
 *  | Gold        | DProject.DSystem.get_payMoney   | 0x01332B44   | 0x021B1A08   |
 *  | Diamonds    | DProject.DSystem.get_Diamond    | 0x01333A50   | 0x021B2660   |
 *  | Tapjoy pts  | DProject.DSystem.get_tapjoyMoney| 0x013329C8   | 0x021B1890   |
 *  | Mascot coin | DProject.DSystem.get_mascotCoin | 0x01335540   | 0x021B3E58   |
 *
 * ============================================================================
 * REPLACEMENT STUB — both return 999,999,999 (0x3B9AC9FF)
 * ============================================================================
 *  ARM32 int64 (cash, gold, diamonds) — 16 bytes:
 *      FF 09 0C E3   movw r0, #0xC9FF
 *      9A 0B 43 E3   movt r0, #0x3B9A      → r0 = 0x3B9AC9FF
 *      00 10 A0 E3   mov  r1, #0           → high word 0
 *      1E FF 2F E1   bx   lr
 *  ARM32 int32 (tapjoy, mascot) — 12 bytes:
 *      FF 09 0C E3 / 9A 0B 43 E3 / 1E FF 2F E1
 *  ARM64 (all five) — 12 bytes; `movz`/`movk` into w0 zero-extends x0, which is correct
 *  for both the int64 getters and the int32 ones:
 *      E0 3F 99 52   movz w0, #0xC9FF
 *      40 73 A7 72   movk w0, #0x3B9A, lsl #16
 *      C0 03 5F D6   ret
 *
 * Overwriting the prologue is safe: the stub returns immediately and **never touches the
 * stack or a callee-saved register**, so the discarded `push`/`sub sp` is simply never
 * executed — no unbalanced SP, no clobbered r4–r11 / x19–x30.
 *
 * ============================================================================
 * ANCHOR UNIQUENESS
 * ============================================================================
 * Short prologues repeat across IL2CPP, so each site uses the smallest window (16–24 bytes)
 * verified to occur **exactly once** in its own library. A missing or ambiguous anchor
 * throws [PatchException] with full context instead of writing into the wrong function.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * `PatchEngine` merges the XAPK/APKS splits into one APK first, then a `rawResourcePatch`
 * anywhere in the set forces `ResourceMode.RAW_ONLY`, so `get(path, true)` resolves the
 * raw-extracted library. Every write here is **same-length in place**, so
 * `detectFileChanges()` catches each on `lastModified` → `ApkUtils.applyTo` overlays them
 * into the rebuilt, re-signed APK.
 *
 * ============================================================================
 * SCOPE — deliberately narrow
 * ============================================================================
 * * `DProject.ProtectedLong.get_storedValue` is NOT patched: it is shared by all three
 *   int64 currencies *and* by every other ProtectedLong use in the game.
 * * No `set_*` method is patched (spend must keep working).
 * * No ads / IAP / gun-unlock behaviour here — separate concerns, separate files.
 * * Both shipped architectures are covered.
 */
@Suppress("unused")
val unlimitedCurrencyPatch = rawResourcePatch(
    name = "Unlimited currency",
    description = "Your cash, gold and diamonds always show the maximum. You never run short of money.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        var patched = 0
        for ((abi, anchors) in listOf(
            "arm64-v8a" to ARM64_ANCHORS,
            "armeabi-v7a" to ARM32_ANCHORS,
        )) {
            val lib = get("lib/$abi/libil2cpp.so", true)
            if (!lib.exists()) {
                println("Dead Target unlimited currency: lib/$abi/libil2cpp.so absent — skipping ABI")
                continue
            }
            applyAnchors(lib, anchors, abi)
            patched++
        }
        if (patched == 0) {
            throw PatchException(
                "Dead Target unlimited currency: neither lib/arm64-v8a nor " +
                    "lib/armeabi-v7a libil2cpp.so is present — unsupported app version or " +
                    "split layout?",
            )
        }
        println("Dead Target unlimited currency: applied to $patched ABI(s)")
    }
}

/**
 * One guarded byte-range replacement in `libil2cpp.so`.
 *
 * [anchorHex] is the ORIGINAL 36- or 48-byte function window (must occur exactly ONCE in
 * the library — that uniqueness is what makes the match self-verifying); [replacementHex]
 * is the stub written over the first bytes of that window (16 bytes for the int64 getters,
 * 12 for the int32 ones).
 */
private class Anchor(
    /** Human label used in logs and [PatchException] messages. */
    val label: String,
    /** Virtual address from Il2CppDumper's script.json (= file offset in this ELF). */
    val va: Long,
    /** Exact file offset — identical to [va] for this 32-bit ELF. */
    val fileOffset: Long,
    /** Hex of the ORIGINAL window — must occur exactly once, or the patch aborts. */
    val anchorHex: String,
    /** Hex of the replacement written at that window's first bytes. */
    val replacementHex: String,
)

/**
 * `movw r0,#0xC9FF ; movt r0,#0x3B9A ; mov r1,#0 ; bx lr` → r0:r1 = 999,999,999 (int64).
 */
private const val STUB_INT64 = "FF090CE39A0B43E30010A0E31EFF2FE1"

/**
 * `movw r0,#0xC9FF ; movt r0,#0x3B9A ; bx lr` → r0 = 999,999,999 (int32).
 */
private const val STUB_INT32 = "FF090CE39A0B43E31EFF2FE1"

private val ARM32_ANCHORS = listOf(
    Anchor(
        label = "DSystem.get_gameMoney -> 999,999,999 (cash, int64)",
        va = 0x013296D8,
        fileOffset = 0x013296D8,
        anchorHex =
            "F04F2DE91CD04DE214439FE50060A0E1" +
                "04408FE00000D4E5000050E30400001A" +
                "00039FE5",
        replacementHex = STUB_INT64,
    ),
    Anchor(
        label = "DSystem.get_payMoney -> 999,999,999 (gold, int64)",
        va = 0x01332B44,
        fileOffset = 0x01332B44,
        // 48 bytes: the 36-byte prologue alone is shared with get_Diamond, which has a
        // byte-identical entry sequence.
        anchorHex =
            "F0472DE918D04DE298529FE50040A0E1" +
                "05508FE00000D5E5000050E30400001A" +
                "84029FE500009FE7EC9FF1EB0100A0E3",
        replacementHex = STUB_INT64,
    ),
    Anchor(
        label = "DSystem.get_Diamond -> 999,999,999 (diamonds, int64)",
        va = 0x01333A50,
        fileOffset = 0x01333A50,
        // 48 bytes for the same reason as gold — these two prologues differ only in the
        // adrp/adr displacement and the class-init call displacement that follow.
        anchorHex =
            "F0472DE918D04DE298529FE50040A0E1" +
                "05508FE00000D5E5000050E30400001A" +
                "84029FE500009FE7299CF1EB0100A0E3",
        replacementHex = STUB_INT64,
    ),
    Anchor(
        label = "DSystem.get_tapjoyMoney -> 999,999,999 (tapjoy points, int32)",
        va = 0x013329C8,
        fileOffset = 0x013329C8,
        // 36 bytes = 9 instructions: `push {r4,lr}` … `ldr r0,[r4,#0x48]` / `pop {r4,pc}`,
        // then the first instruction of the adjacent setter. Only the first 12 bytes are
        // written, so the setter is untouched.
        anchorHex =
            "10402DE9F04090E5000054E30000001A" +
                "E6A0F1EB480094E51080BDE830482DE9" +
                "F05090E5",
        replacementHex = STUB_INT32,
    ),
    Anchor(
        label = "DSystem.get_mascotCoin -> 999,999,999 (mascot coin, int32)",
        va = 0x01335540,
        fileOffset = 0x01335540,
        anchorHex =
            "30482DE9F05090E5000055E30100001A" +
                "0040A0E10796F1EBD40095E53088BDE8" +
                "0050A0E1",
        replacementHex = STUB_INT32,
    ),
)

/**
 * `movz w0,#0xC9FF ; movk w0,#0x3B9A, lsl #16 ; ret` → x0 = 999,999,999.
 *
 * Writing `w0` zero-extends into `x0`, so the same 12-byte stub is correct for the three
 * int64 getters and the two int32 getters; no separate `mov x1,#0` is needed.
 */
private const val STUB_ARM64 = "E03F99524073A772C0035FD6"

/**
 * The five getters on arm64-v8a. VAs come from Il2CppDumper; `file offset = VA - 0x4000`.
 */
private val ARM64_ANCHORS = listOf(
    Anchor(
        label = "DSystem.get_gameMoney -> 999,999,999 (cash, int64)",
        va = 0x021AB024,
        fileOffset = 0x021A7024,
        anchorHex = "FF8301D1FE1300F9F85F03A9F65704A9F44F05A9147D01D0",
        replacementHex = STUB_ARM64,
    ),
    Anchor(
        label = "DSystem.get_payMoney -> 999,999,999 (gold, int64)",
        va = 0x021B1A08,
        fileOffset = 0x021ADA08,
        anchorHex = "FF4301D1FE0B00F9F85F02A9F65703A9F44F04A9F47C0190",
        replacementHex = STUB_ARM64,
    ),
    Anchor(
        label = "DSystem.get_Diamond -> 999,999,999 (diamonds, int64)",
        va = 0x021B2660,
        fileOffset = 0x021AE660,
        anchorHex = "FF4301D1FE0B00F9F85F02A9F65703A9F44F04A9D47C01F0",
        replacementHex = STUB_ARM64,
    ),
    Anchor(
        label = "DSystem.get_tapjoyMoney -> 999,999,999 (tapjoy points, int32)",
        va = 0x021B1890,
        fileOffset = 0x021AD890,
        anchorHex = "FE0F1FF808D040F9880000B4008140B9",
        replacementHex = STUB_ARM64,
    ),
    Anchor(
        label = "DSystem.get_mascotCoin -> 999,999,999 (mascot coin, int32)",
        va = 0x021B3E58,
        fileOffset = 0x021AFE58,
        anchorHex = "FE0F1EF8F44F01A908D040F9A80000B4005141B9",
        replacementHex = STUB_ARM64,
    ),
)

/**
 * Applies every [Anchor] to [lib], guarded by a unique-anchor search.
 *
 * Two-phase so a bad build fails with **zero** bytes written:
 *  1. the library is slurped once and every window is searched for, requiring **exactly
 *     one** occurrence;
 *  2. each resolved offset is re-read through a [RandomAccessFile], compared against the
 *     original bytes, and only then overwritten.
 *
 * The writes are same length as the bytes they replace, so the patcher's
 * `lastModified`-keyed change diff picks them up. Throws [PatchException] with full context
 * if an anchor is missing or ambiguous, or if the bytes on disk are not what we expect —
 * i.e. a new game build / different ABI variant.
 */
private fun applyAnchors(lib: File, anchors: List<Anchor>, abi: String) {
    println(
        "Dead Target unlimited currency: patching lib/$abi/libil2cpp.so " +
            "(${lib.length()} bytes)",
    )
    val bytes = lib.readBytes()

    // Phase 1 — resolve every anchor, or abort before touching anything.
    val writes = anchors.map { anchor ->
        val needle = hex(anchor.anchorHex)
        val replacement = hex(anchor.replacementHex)
        val hits = indexOfAll(bytes, needle)
        when {
            hits.isEmpty() -> throw PatchException(
                "Dead Target unlimited currency: ${anchor.label} — ${needle.size}-byte anchor " +
                    "not found in ${lib.name} (size=${bytes.size}). Expected file offset 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}). " +
                    "Unsupported app version or ABI?",
            )

            hits.size > 1 -> throw PatchException(
                "Dead Target unlimited currency: ${anchor.label} — ${needle.size}-byte anchor is " +
                    "AMBIGUOUS (${hits.size} occurrences: " +
                    hits.joinToString(", ") { "0x" + it.toString(16) } +
                    "). Refusing to guess — unsupported app version?",
            )
        }
        val at = hits[0].toLong()
        if (at + replacement.size > bytes.size) {
            throw PatchException(
                "Dead Target unlimited currency: ${anchor.label} — resolved file offset 0x" +
                    "${at.toString(16)} is past end of ${lib.name} (size=${bytes.size}) — " +
                    "app layout changed?",
            )
        }
        val original = needle.copyOf(replacement.size)
        if (!bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size).contentEquals(original)) {
            throw PatchException(
                "Dead Target unlimited currency: ${anchor.label} — anchor mismatch at VA 0x" +
                    "${anchor.va.toString(16)} (file 0x${at.toString(16)}): expected " +
                    "${toHex(original)} vs found " +
                    "${toHex(bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size))}. " +
                    "libil2cpp.so layout changed — unsupported app version?",
            )
        }
        if (at != anchor.fileOffset) {
            println(
                "Dead Target unlimited currency: ${anchor.label} — NOTE: unique anchor resolved " +
                    "to 0x${at.toString(16)}, documented file offset is 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}).",
            )
        }
        Triple(anchor, at, replacement)
    }

    // Phase 2 — guarded in-place writes.
    RandomAccessFile(lib, "rw").use { raf ->
        for ((anchor, at, replacement) in writes) {
            val original = hex(anchor.anchorHex).copyOf(replacement.size)
            raf.seek(at)
            val actual = ByteArray(replacement.size)
            raf.readFully(actual)
            if (!actual.contentEquals(original)) {
                throw PatchException(
                    "Dead Target unlimited currency: ${anchor.label} — original bytes changed " +
                        "between resolve and write at file 0x${at.toString(16)}: expected " +
                        "${toHex(original)} vs found ${toHex(actual)}.",
                )
            }
            raf.seek(at)
            raf.write(replacement)
            println(
                "Dead Target unlimited currency: VA 0x${anchor.va.toString(16)} " +
                    "(file 0x${at.toString(16)}): ${toHex(original)} -> " +
                    "${anchor.replacementHex}  [${anchor.label}]",
            )
        }
    }
}

/** Every offset at which [needle] occurs in [haystack]. */
private fun indexOfAll(haystack: ByteArray, needle: ByteArray): List<Int> {
    if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
    val hits = mutableListOf<Int>()
    var i = 0
    val last = haystack.size - needle.size
    while (i <= last) {
        var j = 0
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) {
            hits.add(i)
            i += needle.size // anchors cannot overlap themselves
        } else {
            i += j + 1
        }
    }
    return hits
}

/** Parses a plain hex string (no separators) into bytes. */
private fun hex(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

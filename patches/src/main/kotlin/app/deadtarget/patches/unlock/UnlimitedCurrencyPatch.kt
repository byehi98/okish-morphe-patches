package app.deadtarget.patches.unlock

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.186.0 — Unlimited ALL in-game currency (armeabi-v7a).
 *
 * ============================================================================
 * WHY THIS FILE CHANGED ARCHITECTURE (4.185.0 → 4.186.0)
 * ============================================================================
 * The 4.185.0 patch set was written against an **arm64-v8a** XAPK
 * (base + `UnityDataAssetPack.apk` + `config.arm64_v8a.apk`, libil2cpp.so = 85,096,080
 * bytes). The 4.186.0 XAPK published for `com.vng.g6.a.zombie` (versionCode 586009263) is
 * **armeabi-v7a only** — the split set is base + `UnityDataAssetPack.apk` +
 * `config.armeabi_v7a.apk`, and libil2cpp.so is 76,435,716 bytes of 32-bit **A32/ARM**
 * code (not Thumb — every function here starts with `push {…, lr}` as an ARM word).
 *
 * That is not a cosmetic difference: the old patch failed at execution with
 * `get("lib/arm64-v8a/libil2cpp.so")` → `FileNotFoundException`, and even with the path
 * fixed, not one AArch64 anchor can match an ARM32 library. So all five sites below were
 * re-derived from a fresh Il2CppDumper run against the 4.186.0 armv7 library.
 *
 * **RVA == FILE OFFSET** in this ELF (Il2CppDumper prints `RVA:`/`Offset:`/`VA:` identical
 * for every method), so the addresses below can be used directly as file offsets. There is
 * no +0x4000 delta trap here; that trap was an arm64-segment artefact of 4.185.0.
 *
 * ============================================================================
 * SITES — five DSystem getters, byte-verified against the shipped libil2cpp.so
 * ============================================================================
 * All five currencies are `DSystem` instance getters, so the value comes back in the
 * `r0` (and `r1` for the 64-bit ones) per the ARM AAPCS. Forcing the GETTER — and only the
 * getter — is the whole trick: every affordability check and every UI read in the game goes
 * through these properties, so they all pass, while `set_*` methods still run normally on
 * spend, so purchases, shops and the save file keep working.
 *
 *  | Currency    | Method                          | RVA = offset | Return | Stub bytes |
 *  |-------------|---------------------------------|--------------|--------|-----------:|
 *  | Cash        | DProject.DSystem.get_gameMoney  | 0x013296D8   | int64  | 16 |
 *  | Gold        | DProject.DSystem.get_payMoney   | 0x01332B44   | int64  | 16 |
 *  | Diamonds    | DProject.DSystem.get_Diamond    | 0x01333A50   | int64  | 16 |
 *  | Tapjoy pts  | DProject.DSystem.get_tapjoyMoney| 0x013329C8   | int32  | 12 |
 *  | Mascot coin | DProject.DSystem.get_mascotCoin | 0x01335540   | int32  | 12 |
 *
 * Every entry is verified as a real function prologue by disassembly, e.g.
 *   cash   0x013296D8: push {r4,r5,r6,r7,r8,r9,r10,r11,lr} / sub sp,sp,#28 / …
 *                     ldr r4,[r6,#0xF0]   ← ProtectedLong holder
 *   tapjoy 0x013329C8: push {r4,lr} / ldr r4,[r0,#0xF0] / cmp / ldr r0,[r4,#0x48]
 *   mascot 0x01335540: push {r4,r5,r11,lr} / ldr r5,[r0,#0xF0] / … / ldr r0,[r5,#0xD4]
 * (`[r0,#0xF0]` is the UserData/ProtectedLong holder in the 32-bit field layout; the int32
 * slots moved from arm64's 0x80/0x150 to 0x48/0xD4.)
 *
 * ============================================================================
 * REPLACEMENT STUB — both return 999,999,999 (0x3B9AC9FF)
 * ============================================================================
 *  int64 (cash, gold, diamonds) — 16 bytes:
 *      FF 09 0C E3   movw r0, #0xC9FF
 *      9A 0B 43 E3   movt r0, #0x3B9A      → r0 = 0x3B9AC9FF
 *      00 10 A0 E3   mov  r1, #0           → high word 0
 *      1E FF 2F E1   bx   lr
 *  int32 (tapjoy, mascot) — 12 bytes:
 *      FF 09 0C E3 / 9A 0B 43 E3 / 1E FF 2F E1
 *
 * Encodings produced with `llvm-mc -triple=armv7-linux-gnueabi -show-encoding`, not
 * hand-assembled. 999,999,999 = 0x3B9AC9FF fits a signed int32, so the int32 getters stay
 * valid.
 *
 * Overwriting the prologue is safe: the stub returns immediately and **never touches the
 * stack or a callee-saved register**, so the discarded `push`/`sub sp` is simply never
 * executed — no unbalanced SP, no clobbered r4–r11.
 *
 * ============================================================================
 * ANCHOR UNIQUENESS — 36/48-byte windows, exactly one hit each
 * ============================================================================
 * The first 12 bytes of the prologues are not unique (the game has thousands of
 * `push {…, lr}` sites), so each entry carries a 36-byte window — 48 bytes for gold and
 * diamonds, whose prologues are byte-identical to each other — and every window was
 * verified to occur **exactly once** in the 76 MB library. A missing or ambiguous anchor
 * throws [PatchException] with full context instead of writing into the wrong function.
 *
 * ============================================================================
 * DELIVERY — how the patched .so lands in the XAPK split pipeline
 * ============================================================================
 * `PatchEngine` runs `ApkMerger.merge()` FIRST, so base + `UnityDataAssetPack.apk` +
 * `config.armeabi_v7a.apk` become ONE merged APK before any patch executes; the config
 * split is what carries `lib/armeabi-v7a/libil2cpp.so`. A `rawResourcePatch` anywhere in
 * the patch set forces `ResourceMode.RAW_ONLY`, so `get(path, true)` resolves the
 * raw-extracted `lib/armeabi-v7a/libil2cpp.so`. Every write here is **same-length in
 * place** (16-over-16, 12-over-12), so `detectFileChanges()` catches each on
 * `lastModified` → `ApkUtils.applyTo` overlays them into the rebuilt APK →
 * `signWithLegacyFallback`.
 *
 * Static file patch (chosen) vs runtime companion `.so`: the `.text` is plaintext on disk,
 * there is no `.so` integrity check in the chain, and the whole bundle is re-signed as one
 * unit — so a static byte edit needs no NDK, no companion `.so` and no `mprotect` dance.
 *
 * ============================================================================
 * SCOPE — deliberately narrow
 * ============================================================================
 * * `DProject.ProtectedLong.get_storedValue` is NOT patched: it is shared by all three
 *   int64 currencies *and* by every other ProtectedLong use in the game.
 * * No `set_*` method is patched (spend must keep working).
 * * No ads / IAP / gun-unlock behaviour here — separate concerns, separate files.
 * * No arm64 branch: 4.186.0 ships armeabi-v7a only.
 */
@Suppress("unused")
val unlimitedCurrencyPatch = rawResourcePatch(
    name = "Unlimited currency",
    description = "Your cash, gold and diamonds always show the maximum. You never run short of money.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        applyAnchors(get("lib/armeabi-v7a/libil2cpp.so", true), ARM32_ANCHORS)
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
private fun applyAnchors(lib: File, anchors: List<Anchor>) {
    println("Dead Target unlimited currency: patching ${lib.name} (${lib.length()} bytes)")
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

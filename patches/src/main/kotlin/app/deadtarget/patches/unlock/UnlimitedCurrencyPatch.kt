package app.deadtarget.patches.unlock

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.183.0 (583009063) — Unlimited ALL in-game currency.
 *
 * Dead Target is Unity IL2CPP: the smali layer is SDK plumbing only, every economy read
 * lives in `libil2cpp.so` (85,048,024 bytes, arm64-v8a, plaintext `.text`). All five
 * currencies are `DSystem` instance getters, so the value comes back in **x0** (AArch64
 * ABI) and the cheapest correct edit is to make each getter *return a constant*.
 *
 * Forcing the GETTER (and only the getter) is the whole trick: every affordability check
 * and every UI read in the game goes through these properties, so they all pass, while
 * `set_gameMoney` / `set_payMoney` / `set_Diamond` / the int32 setters still run normally
 * on spend — so purchases, shops and the save file keep working. No `set_*` method is
 * touched.
 *
 * ============================================================================
 * SITES — five DSystem getters, byte-verified against the shipped libil2cpp.so
 * ============================================================================
 * ⚠️ **Il2CppDumper `script.json` Address is a VIRTUAL address.** Dead Target's
 * executable LOAD segment maps `file 0x1C1273C -> VA 0x1C1673C`, i.e. **delta = +0x4000**,
 * so **file offset = VA − 0x4000**. Re-verified by parsing the ELF program headers
 * (LOAD R E, off=0x1c1273c vaddr=0x1c1673c). Same trap documented in
 * AliensDriveMeCrazy's KDoc. Writing at the raw VA silently corrupts the library.
 *
 *  | Currency    | Method                            | VA        | File offset | Ret   |
 *  |-------------|-----------------------------------|-----------|-------------|-------|
 *  | Cash        | DProject.DSystem.get_gameMoney    | 0x0218FD78| 0x0218BD78  | int64 |
 *  | Gold        | DProject.DSystem.get_payMoney     | 0x021965DC| 0x021925DC  | int64 |
 *  | Diamonds    | DProject.DSystem.get_Diamond      | 0x02197234| 0x02193234  | int64 |
 *  | Tapjoy pts  | DProject.DSystem.get_tapjoyMoney  | 0x02196464| 0x02192464  | int32 |
 *  | Mascot coin | DProject.DSystem.get_mascotCoin   | 0x02198A2C| 0x02194A2C  | int32 |
 *
 * Original 36 bytes at each file offset (the anchor) → 12-byte replacement:
 *
 *  Cash       0x0218BD78  FF 83 01 D1 FE 13 00 F9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9
 *                        D4 7C 01 B0 88 0A 49 39 F3 03 00 AA C8 00 00 37
 *             →           E0 3F 99 D2 40 73 A7 F2 C0 03 5F D6
 *  Gold       0x021925DC  FF 43 01 D1 FE 0B 00 F9 F8 5F 02 A9 F6 57 03 A9 F4 4F 04 A9
 *                        94 7C 01 D0 88 2A 49 39 F3 03 00 AA C8 00 00 37
 *             →           E0 3F 99 D2 40 73 A7 F2 C0 03 5F D6
 *  Diamonds   0x02193234  FF 43 01 D1 FE 0B 00 F9 F8 5F 02 A9 F6 57 03 A9 F4 4F 04 A9
 *                        94 7C 01 B0 88 42 49 39 F3 03 00 AA C8 00 00 37
 *             →           E0 3F 99 D2 40 73 A7 F2 C0 03 5F D6
 *  Tapjoy     0x02192464  FE 0F 1F F8 08 D0 40 F9 88 00 00 B4 00 81 40 B9 FE 07 41 F8
 *                        C0 03 5F D6 9D FB F3 97 FE 0F 1F F8 08 D0 40 F9
 *             →           E0 3F 99 52 40 73 A7 72 C0 03 5F D6
 *  Mascot     0x02194A2C  FE 0F 1E F8 F4 4F 01 A9 08 D0 40 F9 A8 00 00 B4 00 51 41 B9
 *                        F4 4F 41 A9 FE 07 42 F8 C0 03 5F D6 F3 03 00 AA
 *             →           E0 3F 99 52 40 73 A7 72 C0 03 5F D6
 *
 * Disassembly of the five entries (confirms these are real function entry points, not
 * mid-body offsets — capstone 5.0.7, arm64):
 *   cash    : sub sp,sp,#0x60 / str x30,[sp,#0x20] / stp x24,x23,[sp,#0x30] / … /
 *             ldr x8,[x19,#0x1A0]  ← the ProtectedLong holder field
 *   gold    : sub sp,sp,#0x50 / str x30,[sp,#0x10] / stp x24,x23,[sp,#0x20] / … /
 *             ldr x8,[x19,#0x1A0]
 *   diamonds: sub sp,sp,#0x50 / str x30,[sp,#0x10] / stp x24,x23,[sp,#0x20] / … /
 *             ldr x8,[x19,#0x1A0]
 *   tapjoy  : str x30,[sp,#-0x10]! / ldr x8,[x0,#0x1A0] / cbz x8,… / ldr w0,[x8,#0x80]
 *   mascot  : str x30,[sp,#-0x20]! / stp x20,x19,[sp,#0x10] / ldr x8,[x0,#0x1A0] /
 *             cbz x8,… / ldr w0,[x8,#0x150]
 * ([x0,#0x1A0] = the UserData/ProtectedLong holder; #0x80 / #0x150 = the two int32 slots.)
 *
 * ============================================================================
 * REPLACEMENT STUB — both return 999,999,999 (0x3B9AC9FF)
 * ============================================================================
 *  int64 (cash, gold, diamonds): E0 3F 99 D2 40 73 A7 F2 C0 03 5F D6
 *      movz  x0, #0xC9FF          (sf=1 → D2)
 *      movk  x0, #0x3B9A, lsl #16 (sf=1 → F2)
 *      ret                        (C0 03 5F D6)
 *  int32 (tapjoy, mascot):       E0 3F 99 52 40 73 A7 72 C0 03 5F D6
 *      movz  w0, #0xC9FF          (sf=0 → 52)
 *      movk  w0, #0x3B9A, lsl #16 (sf=0 → 72)
 *      ret
 *
 * Bit 31 (`sf`) is the ONLY difference between the two stubs — the `w` vs `x` encodings are
 * asserted per-site in the anchor table, so the two int32 sites get `…52`/`…72` and the
 * three int64 sites get `…D2`/`…F2`.
 * 999,999,999 = 0x3B9AC9FF fits int32 (max 2,147,483,647), so the int32 getters stay valid.
 *
 * Encoding cross-validated: a MOVZ/MOVK encoder reproduces all four words, the same encoder
 * reproduces `movz w0,#1` = 0x52800020, and that exact `movz w0,#1 ; ret` stub occurs
 * **1,735** times in this library — so the layout matches what the IL2CPP compiler emits
 * rather than a hand-rolled guess. Neither stub occurs anywhere in the shipped library, so
 * the write cannot collide with existing code.
 *
 * Overwriting the prologue is safe: the stub returns immediately and **never touches the
 * stack**, so the discarded `sub sp,sp,#0x60` / `str x30,[sp,…]` / `stp x24,x23,[sp,…]`
 * register-save prologue is simply never executed — no unbalanced sp, no leak.
 *
 * ============================================================================
 * ANCHOR UNIQUENESS — 12 bytes would be dangerously ambiguous, 36 bytes is exact
 * ============================================================================
 * The 12-byte prologues are NOT unique anywhere in the 85 MB lib:
 *   cash 313 hits · gold 418 hits · diamonds 418 hits (gold and diamonds share a
 *   byte-identical first 12 bytes) · tapjoy 40 hits · mascot 6 hits.
 * A 12-byte search would return an arbitrary earlier hit and corrupt an unrelated function.
 *
 * The **36-byte** window is exactly **1 hit** for all five sites, and in every case the
 * single hit is the expected file offset.
 *
 * What the 36 bytes cover per site (worth knowing when retargeting a version):
 *  - cash / gold / diamonds: 36 bytes = 9 instructions, entirely inside the getter
 *    (prologue + `adrp` + init-guard flag test).
 *  - tapjoy: the getter is only 32 bytes (`str x30,[sp,#-0x10]!` … `ret` + a trailing
 *    out-of-line `bl`), so bytes 32..36 are the first 8 bytes of the ADJACENT setter
 *    (whose prologue is identical). Only the first 12 bytes are ever written, so the setter
 *    is untouched — but the anchor deliberately spans it, because that is what makes the
 *    tapjoy match unique (its 12-byte count is only unique from 16 bytes up).
 *  - mascot: 36 bytes = 9 instructions = exactly the getter, ending on its `ret`.
 *
 * Every write is additionally guarded by read-original-bytes-then-compare (see
 * [applyAnchors]) and by the "exactly one occurrence" check, so a new build fails loudly
 * with a [PatchException] instead of writing into the wrong function.
 *
 * ============================================================================
 * DELIVERY — how the patched .so lands in the XAPK split pipeline
 * ============================================================================
 * Javap-verified against morphe-patcher 1.5.2 (see Dead Trigger's NativeIapVerifierBypass
 * KDoc for the full read-writeup of the classes involved):
 *  1. The CLI input is the `.xapk`; `PatchEngine` runs `ApkMerger.merge()` FIRST, so
 *     `base.apk` + `UnityDataAssetPack.apk` + `config.arm64_v8a.apk` become ONE merged APK
 *     before any patch executes. The config split is what carries `lib/arm64-v8a/libil2cpp.so`.
 *  2. A `rawResourcePatch` anywhere in the patch set forces `ResourceMode.RAW_ONLY`: decode
 *     = `ApkModuleRawDecoder` raw-extract of the merged APK into the working dir, libs at
 *     `root/lib/<abi>/libil2cpp.so`. `get(path, true)` resolves exactly there (the boolean
 *     is a vestigial `uncompress` hint, unused by the Arsclib coder).
 *  3. On encode, `detectFileChanges()` re-snapshots the extracted root tree — these are
 *     SAME-LENGTH in-place writes (12 bytes over 12 bytes), so only `lastModified` changes,
 *     which is exactly what the diff keys on — landing in `modifiedBinaryResources` →
 *     `getOtherResourceFiles(RAW_ONLY)` → `ApkUtils.applyTo` overlays them into the rebuilt
 *     APK → `signWithLegacyFallback`.
 *  4. Roundtrip losslessness for this exact pipeline shape was previously proven on Dead
 *     Trigger by CRC-comparing a patched output's two `libil2cpp.so` copies against the
 *     original config splits.
 *
 * Static file patch (chosen) vs runtime companion `.so`: the `.text` is plaintext on disk,
 * there is NO `.so` integrity / signature / anti-tamper check anywhere in the chain
 * (protection-bypass.md: no pairip, no Java signature verification, and VNG `libpglarmor.so`
 * is not even Java-loaded), and the whole bundle is re-signed as one unit — so a static byte
 * edit needs no NDK, no companion `.so`, no `System.loadLibrary` trigger and no `mprotect`
 * dance. It also sidesteps the ubisoftpop packed-lib timing lesson outright: nothing is
 * touched at runtime, before or after Unity's native init. (Dead Trigger's KDoc made the
 * same choice over the ubisoftpop companion-`.so` route.)
 *
 * ============================================================================
 * SCOPE — deliberately narrow
 * ============================================================================
 * * `DProject.ProtectedLong.get_storedValue` (0x2ACB124) is NOT patched: it is shared by all
 *   three int64 currencies *and* by every other ProtectedLong use in the game, so it would
 *   over-reach. The five DSystem getters are precisely scoped.
 * * No `set_*` method is patched (spend must keep working).
 * * No ads / IAP-receipt / premium-entitlement / gun-unlock / god-mode behaviour is included
 *   here — those are separate concerns, deliberately out of scope for this patch.
 * * No armeabi-v7a branch: this XAPK ships **arm64-v8a ONLY** (splits: base +
 *   `UnityDataAssetPack.apk` + `config.arm64_v8a.apk`), so a second anchor table would be
 *   dead code.
 * * `DSystem` also has built-in cheat fields (`showcheat`, `EnableGod`, …) and dev cheats
 *   (`UnlockAllGuns`, `UnlockGunByIAPOK`) — reachable only with a runtime companion `.so`;
 *   deliberately not touched by this static currency patch.
 *
 * Discovery: a top-level `val … = rawResourcePatch(…)` IS a public static Patch-valued field,
 * which is what `PatchLoader` scans, so list-patches shows exactly one Dead Target entry.
 */
@Suppress("unused")
val unlimitedCurrencyPatch = rawResourcePatch(
    name = "Unlimited currency",
    description = "Your cash, gold and diamonds always show the maximum. You never run short of money.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        // arm64-v8a only — this XAPK has no armeabi-v7a split.
        applyAnchors(get("lib/arm64-v8a/libil2cpp.so", true), ARM64_ANCHORS)
    }
}

/**
 * One guarded byte-range replacement in `libil2cpp.so`.
 *
 * [anchorHex] is the ORIGINAL 36-byte function window (must occur exactly ONCE in the
 * library — that uniqueness is what makes the match self-verifying); [replacementHex] is the
 * 12-byte stub written over the first 12 bytes of that window.
 */
private class Anchor(
    /** Human label used in logs and [PatchException] messages. */
    val label: String,
    /** VIRTUAL address from Il2CppDumper's script.json (kept for messages only). */
    val va: Long,
    /** Documented exact file offset = va − 0x4000 (exec LOAD delta, see KDoc). */
    val fileOffset: Long,
    /** Hex of the ORIGINAL 36-byte window — must occur exactly once, or the patch aborts. */
    val anchorHex: String,
    /** Hex of the 12-byte replacement written at that window's first 12 bytes. */
    val replacementHex: String,
)

/**
 * `movz x0,#0xC9FF ; movk x0,#0x3B9A,lsl#16 ; ret` → x0 = 0x3B9AC9FF = 999,999,999 (int64).
 */
private const val STUB_INT64 = "E03F99D24073A7F2C0035FD6"

/**
 * `movz w0,#0xC9FF ; movk w0,#0x3B9A,lsl#16 ; ret` → w0 = 0x3B9AC9FF = 999,999,999 (int32).
 */
private const val STUB_INT32 = "E03F99524073A772C0035FD6"

private val ARM64_ANCHORS = listOf(
    Anchor(
        label = "DSystem.get_gameMoney -> 999,999,999 (cash, int64)",
        va = 0x0218FD78,
        fileOffset = 0x0218BD78,
        anchorHex =
            "FF8301D1FE1300F9F85F03A9" +
            "F65704A9F44F05A9D47C01B0" +
            "880A4939F30300AAC8000037",
        replacementHex = STUB_INT64,
    ),
    Anchor(
        label = "DSystem.get_payMoney -> 999,999,999 (gold, int64)",
        va = 0x021965DC,
        fileOffset = 0x021925DC,
        anchorHex =
            "FF4301D1FE0B00F9F85F02A9" +
            "F65703A9F44F04A9947C01D0" +
            "882A4939F30300AAC8000037",
        replacementHex = STUB_INT64,
    ),
    Anchor(
        label = "DSystem.get_Diamond -> 999,999,999 (diamonds, int64)",
        va = 0x02197234,
        fileOffset = 0x02193234,
        anchorHex =
            "FF4301D1FE0B00F9F85F02A9" +
            "F65703A9F44F04A9947C01B0" +
            "88424939F30300AAC8000037",
        replacementHex = STUB_INT64,
    ),
    Anchor(
        label = "DSystem.get_tapjoyMoney -> 999,999,999 (tapjoy points, int32)",
        va = 0x02196464,
        fileOffset = 0x02192464,
        // This getter is 32 bytes; bytes 32..36 are the adjacent setter's first two
        // instructions — kept in the anchor because that is what makes it unique, but
        // NEVER written (the replacement is 12 bytes).
        anchorHex =
            "FE0F1FF808D040F9880000B4" +
            "008140B9FE0741F8C0035FD6" +
            "9DFBF397FE0F1FF808D040F9",
        replacementHex = STUB_INT32,
    ),
    Anchor(
        label = "DSystem.get_mascotCoin -> 999,999,999 (mascot coin, int32)",
        va = 0x02198A2C,
        fileOffset = 0x02194A2C,
        anchorHex =
            "FE0F1EF8F44F01A908D040F9" +
            "A80000B4005141B9F44F41A9" +
            "FE0742F8C0035FD6F30300AA",
        replacementHex = STUB_INT32,
    ),
)

/**
 * Applies every [Anchor] to [lib], guarded by a unique-anchor search.
 *
 * Two-phase so a bad build fails with **zero** bytes written:
 *  1. the library is slurped once and every 36-byte anchor is searched for, requiring
 *     **exactly one** occurrence (12 bytes alone is hopelessly ambiguous —
 *     313/418/418/40/6 hits — and a wrong hit would corrupt an unrelated function);
 *  2. each resolved offset is re-read through a [RandomAccessFile], compared against the
 *     original bytes, and only then overwritten.
 *
 * The write is the same length as the bytes it replaces (12 over 12), so the patcher's
 * `lastModified`-keyed change diff picks it up. Throws [PatchException] with full context
 * if an anchor is missing or ambiguous, or if the bytes on disk are not what we expect —
 * i.e. new game build, unsupported version.
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
                "Dead Target unlimited currency: ${anchor.label} — 36-byte anchor not found in " +
                    "${lib.name} (size=${bytes.size}). Expected file offset 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}). " +
                    "Unsupported app version?",
            )

            hits.size > 1 -> throw PatchException(
                "Dead Target unlimited currency: ${anchor.label} — 36-byte anchor is AMBIGUOUS " +
                    "(${hits.size} occurrences: " +
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
        val original12 = needle.copyOf(replacement.size)
        if (!bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size).contentEquals(original12)) {
            throw PatchException(
                "Dead Target unlimited currency: ${anchor.label} — anchor mismatch at VA 0x" +
                    "${anchor.va.toString(16)} (file 0x${at.toString(16)}): expected " +
                    "${toHex(original12)} vs found ${toHex(bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size))}. " +
                    "libil2cpp.so layout changed — unsupported app version?",
            )
        }
        if (at != anchor.fileOffset) {
            // Not fatal — the unique anchor is authoritative — but the .so moved, so say so
            // loudly instead of silently shipping.
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
            val original12 = hex(anchor.anchorHex).copyOf(replacement.size)
            raf.seek(at)
            val actual = ByteArray(replacement.size)
            raf.readFully(actual)
            if (!actual.contentEquals(original12)) {
                throw PatchException(
                    "Dead Target unlimited currency: ${anchor.label} — original bytes changed " +
                        "between resolve and write at file 0x${at.toString(16)}: expected " +
                        "${toHex(original12)} vs found ${toHex(actual)}.",
                )
            }
            raf.seek(at)
            raf.write(replacement)
            println(
                "Dead Target unlimited currency: VA 0x${anchor.va.toString(16)} " +
                    "(file 0x${at.toString(16)}): ${toHex(original12)} -> " +
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
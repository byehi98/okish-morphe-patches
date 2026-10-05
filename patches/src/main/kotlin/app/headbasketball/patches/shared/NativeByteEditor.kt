package app.headbasketball.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One guarded native edit inside a shipped `libil2cpp.so`.
 *
 * `verifyHex` and `replacementHex` are **independent axes**: the verify window is
 * how the anchor is *proven unique*, the replacement is how many bytes actually
 * change. The only relationship enforced is that the replacement fits inside the
 * verified window, so every byte that can be touched is covered by the uniqueness
 * proof.
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property rva virtual address in the shipped lib (what disassembly shows).
 * @property fileOffset exact on-disk offset (arm64: `rva - 0x4000`; armv7: `rva`).
 * @property verifyHex original bytes at [fileOffset]; must occur exactly once in the lib.
 * @property replacementHex bytes written at [fileOffset].
 */
internal class NativeAnchor(
    val label: String,
    val rva: Long,
    val fileOffset: Long,
    val verifyHex: String,
    val replacementHex: String,
) {
    val verifyBytes: ByteArray = hexToBytes(verifyHex)
    val replacementBytes: ByteArray = hexToBytes(replacementHex)

    init {
        require(verifyBytes.isNotEmpty()) { "$label: empty verifyHex" }
        require(replacementBytes.isNotEmpty()) { "$label: empty replacementHex" }
        require(replacementBytes.size <= verifyBytes.size) {
            "$label: replacement is ${replacementBytes.size} B but the verify window is " +
                "${verifyBytes.size} B — the write would escape the uniqueness proof"
        }
    }
}

/** One ABI's worth of edits — every anchor is validated before any is written. */
internal class AbiEdits(
    val abi: String,
    val anchors: List<NativeAnchor>,
)

/** The `libil2cpp.so` per-ABI entry inside the APK. */
private const val LIBIL2CPP = "libil2cpp.so"

/** Streaming granularity for the whole-file uniqueness scan (plus needle overlap). */
private const val SCAN_CHUNK = 1 shl 20

/**
 * ============================================================================
 * WHAT THIS EDITS, AND WHY A `rawResourcePatch` IS THE ONLY WAY
 * ============================================================================
 * Head Basketball 4.6.4 is Unity **2022.3.62f3** with the **IL2CPP** scripting
 * backend. Every piece of game logic — the currency model, the whole purchase and
 * grant pipeline, the ad pipeline — is C# compiled to native ARM code inside
 * `lib/<abi>/libil2cpp.so` (arm64-v8a **61,223,136 B**, armeabi-v7a **53,216,188 B**).
 * There is no smali to fingerprint, and no DEX-side hook can change what
 * `DataHelper.GetTotalPoint()` *returns* or what `CommonHelper.BuyItem()` *does*.
 * So both shipped patches are **static in-place byte edits of the shipped lib**.
 *
 * A `bytecodePatch` physically cannot do this: its context is
 * `BytecodePatchContext`, and the file API (`get(path, uncompress)`) exists only on
 * `ResourcePatchContext` (javap-verified against morphe-patcher 1.5.2 — the same
 * finding Dead Trigger's `NativeIapVerifierBypass.kt` documents). A
 * `rawResourcePatch` sitting anywhere in the patch graph forces
 * `ResourceMode.RAW_ONLY`, which is what makes the raw-extracted
 * `root/lib/<abi>/libil2cpp.so` exist on disk and be resolvable via `get(...)`.
 *
 * ============================================================================
 * WHY NOT A RUNTIME COMPANION `.so` (the usual IL2CPP answer)
 * ============================================================================
 * A `libil2cpp.so` patch here needs no NDK build, no `System.loadLibrary` trigger,
 * no `mprotect` dance and no icache flush, because:
 *  - the shipped lib's `.text` is plaintext on disk and standard-stripped (all 241
 *    `il2cpp_*` exports are present), and
 *  - there is **no anti-tamper of any kind** to defeat — no signature check, no
 *    self-hash, no `classes.dex` CRC, no Play Integrity / SafetyNet / App Check, no
 *    root / emulator / Frida / Xposed detection, no packer, no VM-style license
 *    interpreter. The whole APK is re-signed as one unit and that is sufficient
 *    (analysis/head-basketball/notes/possible-patches.md §3, T1 row).
 * This also sidesteps the ubisoftpop packed-lib timing lesson outright: nothing is
 * touched at runtime, before or after Unity's native init.
 *
 * ============================================================================
 * RVA → FILE OFFSET (re-derived with `readelf -lW`, not assumed)
 * ============================================================================
 * ```
 * arm64-v8a  LOAD(1) off 0x000000   vaddr 0x000000000  R
 *            LOAD(2) off 0x15048f4 vaddr 0x15088f4   R E   <-- executable
 *            LOAD(3) off 0x35ea310 vaddr 0x35f2310   RW
 *            LOAD(4) off 0x37e4618 vaddr 0x37f0618   RW
 *      =>  fileOffset = RVA - 0x4000
 *
 * armeabi-v7a LOAD(1) off 0x000000  vaddr 0x000000000  R E   <-- single R E segment
 *      =>  fileOffset = RVA  (delta 0)
 * ```
 * The arm64 `+0x4000` exists because IL2CPP's executable LOAD segment starts at file
 * offset `0x15048f4` but at virtual address `0x15088f4` — the linker inserted a
 * 16 KiB alignment gap, so every arm64 address is 0x4000 lower on disk than in
 * memory. The armv7 build has a **single** `R E` LOAD at offset 0 / vaddr 0, so its
 * delta is **0**. The armv7 lib is also **A32, not Thumb** (its ELF entry decodes as
 * ARM, not Thumb-2), so every armv7 RVA here is a plain ARM word address with no
 * low-bit-set Thumb convention to strip.
 *
 * ============================================================================
 * WHY A 32-BYTE VERIFY WINDOW WITH A WHOLE-FILE UNIQUENESS CHECK
 * ============================================================================
 * This is the single most important implementation rule for this app, and it is a
 * direct lesson from Dead Target, whose 12-byte anchors measured
 * **313 / 418 / 418 / 40 / 6** hits in its own target lib.
 *
 * Measured on the shipped Head Basketball arm64 lib (61,223,136 B), the standard
 * IL2CPP function prologue is catastrophically ambiguous:
 * ```
 *   `str x30,[sp,#-0x20]!` alone                  -> 24,078 hits
 *   + `stp x20,x19,[sp,#0x10]`            ( 8 B)  -> 24,078 hits
 *   + `adrp x20, …`                       (12 B)  -> 24,078 hits
 * ```
 * A 4-byte or 8-byte anchor is therefore worthless: blind-searching the lib for the
 * N2 prologue and writing over the first match would corrupt an arbitrary function.
 * Every anchor below carries a **32-byte verify window** (24 on armv7, where the
 * prologue is shorter) and this engine additionally requires that window to occur
 * **exactly once in the entire `.so`** — 0 hits means the offset is wrong, >1 means
 * the window is ambiguous, and both abort the patch.
 *
 * Independently: Dead Trigger's shipped patch compares only the 4 anchor bytes and
 * never checks uniqueness. That weakness is **deliberately not replicated here**.
 *
 * **Replacement length is an INDEPENDENT axis from verify length.** N1 writes 12
 * bytes over a 32-byte-verified window; N2 writes 4 bytes over a 32-byte-verified
 * window; N1's armv7 twin writes 12 bytes over a 24-byte-verified window.
 * [NativeAnchor] models `verifyHex` and `replacementHex` as separate fields and
 * asserts only that the replacement *fits inside* the verified window, so the
 * uniqueness guarantee covers every byte that can be touched.
 *
 * ============================================================================
 * TWO-PHASE RESOLVE-THEN-WRITE (never leave the lib half-patched)
 * ============================================================================
 * [applyNativeEdits] resolves and writes per ABI, and each ABI's edits run in two
 * phases over a single open `RandomAccessFile`:
 *  1. **RESOLVE** — for *every* anchor: bounds-check the window against the file
 *     length, read the window at the offset and compare it byte-for-byte with
 *     `verifyHex`, then stream the **whole file** and count occurrences of
 *     `verifyHex`. Nothing is written.
 *  2. **WRITE** — reached only if phase 1 passed for *all* of that ABI's anchors;
 *     seek and write `replacementHex` in place, then re-assert the file length.
 * Any failure in phase 1 throws [PatchException] naming the ABI, the RVA, the file
 * offset, the expected vs found window hex and the whole-file hit count — so a
 * moved/changed app build fails loudly with **zero bytes written** and never leaves
 * the lib half-patched.
 *
 * Only small byte ranges are ever read and written; the uniqueness scan streams the
 * lib through a 1 MiB + overlap buffer instead of slurping 61 MB into the heap
 * (61 MB × 3 anchors ≈ 175 MB of sequential reads, ≈1 s).
 *
 * ============================================================================
 * ABI HANDLING
 * ============================================================================
 * `get("lib/<abi>/libil2cpp.so", true)` resolves inside the RAW_ONLY working dir.
 * ⚠️ `listApkEntries("lib/")` returns **EMPTY** for this app — the lib entries are
 * DEFLATE-compressed (72 % / 68 %) rather than STOREd, so the entry-listing API sees
 * nothing. The Crossy Road trap. **Never use the list API here**; resolve the known
 * path directly.
 *
 * An ABI whose lib is simply absent from the APK is skipped with a log line rather
 * than failing the run: `morphe-cli --striplibs arm64-v8a` legitimately removes the
 * unpatched armv7 lib, and there is nothing to patch for an ABI that is not
 * shipped. If *no* targeted ABI is present at all, that is an error and throws.
 *
 * ============================================================================
 * IN-PLACE EDIT ⇒ SAFE ROUND-TRIP
 * ============================================================================
 * Every edit is in place and same-length, so a lib's length never changes. On encode,
 * `detectFileChanges()` re-snapshots the extracted root tree (lastModified + length);
 * our write bumps the mtime, so the file lands in `modifiedBinaryResources`,
 * `getOtherResourceFiles(RAW_ONLY)` returns it, and `ApkUtils.applyTo` overlays it
 * into `rebuilt.apk`, which is then re-signed. DEFLATE re-compression is applied to
 * the edited bytes and the round-trip is byte-lossless for lib content.
 *
 * Because both patches touch **disjoint** offsets of the same file (N1 arm64
 * `0x1C10518`, N1 armv7 `0x119A8D0`, N2 arm64 `0x1E88E30`), enabling both is safe
 * and order-independent.
 *
 * ============================================================================
 * DO NOT — packaging rules this patch set must never violate
 * ============================================================================
 * Head Basketball ships its content in a **419 MB OBB**, not in split APKs, and
 * `morphe-cli` has **no concept of OBBs**. Unity resolves the expansion as
 * `%s/main.%d.%s.obb`. Therefore, when editing this app:
 *
 * 1. **NEVER bump `versionCode` (471).** The OBB is named
 *    `main.471.com.dnddream.HeadBasketball.obb` — the `471` *is* the versionCode, so
 *    any bump makes the 419 MB OBB unfindable and the game unplayable on a device
 *    that already installed the official release. Morphe does not touch it by
 *    default; a patch that needs to must also rename the OBB, which it cannot,
 *    because the OBB is not inside the APK.
 * 2. **NEVER touch `assets/unity_obb_guid`** (`8613441d-1d79-44ca-8c7a-2b4bc78f1475`,
 *    36 bytes, no trailing newline). It must stay byte-identical to the OBB's own
 *    copy or `libunity.so` aborts with *"Application OBB has mismatching GUID"*.
 * 3. **NEVER touch `assets/bin/Data/Managed/Metadata/global-metadata.dat`.** It is
 *    inside the APK (unusual for an OBB game), and **every RVA in these patches
 *    depends on it surviving the round-trip byte-identical**.
 * 4. Keep `READ_EXTERNAL_STORAGE` and `android:installLocation="preferExternal"` —
 *    the OBB lives on external storage.
 * 5. Do not delete `assets/bin/Data/data.unity3d` (750,735 B — the boot/splash
 *    scene stub).
 * 6. Do not add a `--striplibs` that drops an ABI you are patching.
 *
 * (Authoritative source: analysis/head-basketball/notes/possible-patches.md §4.)
 */
internal fun ResourcePatchContext.applyNativeEdits(
    patchName: String,
    targets: List<AbiEdits>,
) {
    var patchedAbis = 0
    for (target in targets) {
        val path = "lib/${target.abi}/$LIBIL2CPP"
        val lib = runCatching { get(path, true) }.getOrNull()?.takeIf { it.isFile }
        if (lib == null) {
            println(
                "$patchName: $path is not present in this APK — skipping the " +
                    "${target.abi} edits (ABI absent or stripped; nothing to patch for it).",
            )
            continue
        }
        applyEditsTo(patchName, lib, target.abi, target.anchors)
        patchedAbis++
    }
    if (patchedAbis == 0) {
        throw PatchException(
            "$patchName: none of the targeted ABIs " +
                "(${targets.joinToString(", ") { it.abi }}) carries a $LIBIL2CPP in this " +
                "APK — nothing was patched. Unsupported app build?",
        )
    }
}

/**
 * Two-phase (resolve-then-write) application of every [anchor] in one [lib].
 *
 * @param patchName patch name, used as the prefix of every log/exception message.
 * @param lib extracted `libil2cpp.so` on disk (opened `"rw"`, same length after).
 * @param abi ABI label (`arm64-v8a` / `armeabi-v7a`) for messages.
 * @param anchors every edit to apply to this ABI — all are validated first.
 * @throws PatchException on a bounds violation, a window mismatch, or a whole-file
 *   hit count that is not exactly 1. Nothing is written in that case.
 */
private fun applyEditsTo(
    patchName: String,
    lib: File,
    abi: String,
    anchors: List<NativeAnchor>,
) {
    require(anchors.isNotEmpty()) { "$patchName ($abi): no anchors supplied" }
    RandomAccessFile(lib, "rw").use { raf ->
        val size = raf.length()
        println(
            "$patchName ($abi): ${lib.name} is $size bytes; resolving " +
                "${anchors.size} edit(s) before writing any.",
        )

        // ── PHASE 1 — resolve + validate ALL anchors, write NOTHING ─────────────
        for (anchor in anchors) {
            val end = anchor.fileOffset + anchor.verifyBytes.size
            if (anchor.fileOffset < 0 || end > size) {
                throw PatchException(
                    "$patchName ($abi): ${anchor.label} — verify window " +
                        "0x${anchor.fileOffset.toString(16)}..0x${end.toString(16)} " +
                        "(${anchor.verifyBytes.size} B) is outside ${lib.name} " +
                        "(size=$size). Wrong file offset for this build?",
                )
            }
            val actual = readAt(raf, anchor.fileOffset, anchor.verifyBytes.size)
            if (!actual.contentEquals(anchor.verifyBytes)) {
                throw PatchException(
                    "$patchName ($abi): ${anchor.label} — verify window mismatch at " +
                        "RVA 0x${anchor.rva.toString(16)} (file " +
                        "0x${anchor.fileOffset.toString(16)}): expected " +
                        "${toHex(anchor.verifyBytes)} but found ${toHex(actual)}. " +
                        "libil2cpp.so layout changed — unsupported app version?",
                )
            }
            val hits = countOccurrences(raf, anchor.verifyBytes)
            if (hits != 1) {
                throw PatchException(
                    "$patchName ($abi): ${anchor.label} — verify window " +
                        "${toHex(anchor.verifyBytes)} at RVA " +
                        "0x${anchor.rva.toString(16)} (file " +
                        "0x${anchor.fileOffset.toString(16)}) occurs $hits time(s) in " +
                        "${lib.name} ($size bytes); exactly 1 is required. A 0-hit window " +
                        "means the offset is wrong, >1 means the window is ambiguous — " +
                        "refusing to patch.",
                )
            }
        }

        // ── PHASE 2 — all anchors validated, so the write cannot half-apply ─────
        for (anchor in anchors) {
            raf.seek(anchor.fileOffset)
            raf.write(anchor.replacementBytes)
            println(
                "$patchName ($abi): RVA 0x${anchor.rva.toString(16)} (file " +
                    "0x${anchor.fileOffset.toString(16)}): wrote " +
                    "${toHex(anchor.replacementBytes)} over ${anchor.verifyBytes.size} " +
                    "uniquely-verified bytes — ${anchor.label}",
            )
        }
        check(raf.length() == size) {
            "$patchName ($abi): ${lib.name} length changed during patching " +
                "($size -> ${raf.length()}) — the edit must be in place"
        }
    }
}

/** Reads exactly [length] bytes at [offset]. */
private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    raf.seek(offset)
    raf.readFully(out)
    return out
}

/**
 * Counts occurrences of [needle] in the **entire** file, streaming through a
 * [SCAN_CHUNK] buffer with a `needle.size - 1` byte carry so matches spanning a chunk
 * boundary are still found. The lib is never loaded into memory whole.
 */
private fun countOccurrences(raf: RandomAccessFile, needle: ByteArray): Int {
    val overlap = needle.size - 1
    val size = raf.length()
    val buffer = ByteArray(SCAN_CHUNK + overlap)
    val first = needle[0]
    var count = 0
    var carry = 0
    var position = 0L
    while (position < size) {
        raf.seek(position)
        val read = minOf(SCAN_CHUNK.toLong(), size - position).toInt()
        raf.readFully(buffer, carry, read)
        val valid = carry + read
        var i = 0
        while (i <= valid - needle.size) {
            if (buffer[i] == first && regionMatches(buffer, i, needle)) {
                count++
                i += needle.size
            } else {
                i++
            }
        }
        carry = minOf(overlap, valid)
        if (carry > 0) System.arraycopy(buffer, valid - carry, buffer, 0, carry)
        position += read
    }
    return count
}

/** True when [needle] equals [buffer] starting at [offset]. */
private fun regionMatches(buffer: ByteArray, offset: Int, needle: ByteArray): Boolean {
    for (k in needle.indices) {
        if (buffer[offset + k] != needle[k]) return false
    }
    return true
}

/** Parses a plain hex string (no separators, no `0x`) into bytes. */
internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length: '$hex'" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** "xx xx xx …" formatter for mismatch messages. */
internal fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
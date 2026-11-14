package app.magicsort.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One guarded native edit inside Magic Sort's shipped `libil2cpp.so`.
 *
 * `verifyHex` and `replacementHex` are **independent axes**: the verify window is
 * how the anchor is *proven unique*, the replacement is how many bytes actually
 * change. The only relationship enforced is that the replacement fits inside the
 * verified window, so every byte that can be touched is covered by the uniqueness
 * proof.
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property rva virtual address in the shipped lib (what Il2CppDumper/disassembly shows).
 * @property fileOffset exact on-disk offset (on both ABIs: **equal to `rva`** — see KDoc below).
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
        require(verifyBytes.size >= MIN_VERIFY_WINDOW) {
            "$label: verify window is only ${verifyBytes.size} B — this app's IL2CPP " +
                "prologues/branches need at least $MIN_VERIFY_WINDOW B to be unambiguous " +
                "(see NativeBytePatch KDoc)"
        }
        require(replacementBytes.size <= verifyBytes.size) {
            "$label: replacement is ${replacementBytes.size} B but the verify window is " +
                "${verifyBytes.size} B — the write would escape the uniqueness proof"
        }
    }
}

/**
 * The per-ABI native edits for one patch, keyed by the split's ABI directory name.
 *
 * 0.0.3480 ships **two** `libil2cpp.so` builds (arm64-v8a 95,838,880 B and
 * armeabi-v7a 86,265,520 B) that share the IL2CPP metadata but are separate
 * compilations: identical C# produces different instructions, different function
 * ordering and therefore different RVAs. Each ABI consequently needs its own
 * [NativeAnchor] list — there is no offset that is valid in both files.
 *
 * @property abi ABI directory under `lib/` (`arm64-v8a`, `armeabi-v7a`).
 * @property anchors edits for that ABI only.
 */
internal class AbiAnchorSet(
    val abi: String,
    val anchors: List<NativeAnchor>,
) {
    init {
        require(abi.isNotBlank()) { "empty ABI name" }
        require(anchors.isNotEmpty()) { "$abi: no native anchors supplied" }
    }

    val libPath: String get() = "lib/$abi/libil2cpp.so"
}

/** Smallest accepted verify window; anything shorter is ambiguous in a ~90 MB lib. */
private const val MIN_VERIFY_WINDOW = 24

/** Streaming granularity for the whole-file uniqueness scan (plus needle overlap). */
private const val SCAN_CHUNK = 1 shl 20

/**
 * ============================================================================
 * WHAT THIS EDITS, AND WHY A `rawResourcePatch` IS THE ONLY WAY
 * ============================================================================
 * Magic Sort is Unity **2021.3.57f2** with the **IL2CPP** scripting backend. Every
 * piece of game logic — the ad pipeline, the IAP grant funnel, the local
 * entitlement getters — is C# compiled to native code inside `lib/<abi>/libil2cpp.so`
 * (95,838,880 B arm64-v8a / 86,265,520 B armeabi-v7a on 0.0.3480). There is no
 * meaningful smali to fingerprint for those behaviours and no DEX-side hook that can
 * change what `InAppPurchaseHistoryService.HasNoAds()` *returns*.
 *
 * A `bytecodePatch` physically cannot do this: its context is
 * `BytecodePatchContext`, and the file API (`get(path, uncompress)`) exists only on
 * `ResourcePatchContext` (javap-verified against morphe-patcher 1.5.2 — the same
 * finding Head Basketball's `NativeByteEditor.kt` and Crossy Road's Free store
 * patch document). A `rawResourcePatch` anywhere in the patch graph forces
 * `ResourceMode.RAW_ONLY`, which is what makes the raw-extracted
 * `root/lib/<abi>/libil2cpp.so` exist on disk and be resolvable via `get(...)`.
 *
 * ============================================================================
 * RVA == FILE OFFSET IN THIS APP (do not "fix" this to rva - 0x4000)
 * ============================================================================
 * `readelf -lW libil2cpp.so` on both 0.0.3480 libs:
 * ```
 * arm64-v8a:   LOAD  off 0x000000   vaddr 0x000000000   filesz 0x5408afc  R E
 *              LOAD  off 0x540a6d8  vaddr 0x000000540e6d8 filesz 0x75b2c0   RW  <-- delta -0x4000
 * armeabi-v7a: LOAD  off 0x000000   vaddr 0x00000000    filesz 0x4e92625  R E
 *              LOAD  off 0x4e965e0  vaddr 0x000004e9a5e0 filesz 0x3ae1a0  RW   <-- delta -0x4000
 * ```
 * The **first** PT_LOAD — the one holding `.text`, `.plt` and `il2cpp` — has
 * `p_offset == p_vaddr == 0x0` on **both** ABIs (`p_align 0x4000`, and the
 * executable segment is the first one). Every anchor in this patch set is below
 * `0x5408afc`, so `fileOffset == rva` exactly. This is the opposite of Head
 * Basketball, whose *executable* LOAD sits at `off 0x15048f4 / vaddr 0x15088f4`
 * (there the executable segment is the second header and every address is 0x4000
 * lower on disk). The second LOAD here does have a `-0x4000` delta, but nothing we
 * patch lives in it — so passing `fileOffset = rva` is correct and the fields are
 * kept separate only so the intent and every log line show both numbers.
 *
 * ============================================================================
 * WHY A 24–40-BYTE VERIFY WINDOW WITH A WHOLE-FILE UNIQUENESS CHECK
 * ============================================================================
 * A 4-byte branch or an 8-byte `mov w0,#1; ret` is worthless as a locator: blind
 * searching a ~90 MB IL2CPP lib for it would hit thousands of unrelated functions.
 * Every anchor therefore carries the **actual instruction sequence around the edit**
 * (24–28 B on arm64, 24–40 B on armeabi-v7a — the 32-bit `HasNoAds` window had to
 * be widened to 40 B because its 24 B form occurs three times), and this engine
 * additionally requires that window to occur **exactly once in the entire `.so`** —
 * 0 hits means the offset is wrong, >1 means the window is ambiguous, and both abort
 * the patch. Uniqueness was measured against both shipped 0.0.3480 libs before these
 * anchors were written; all six occur once per ABI.
 *
 * Note that the verify/replacement split matters: the branch anchors verify 24 bytes
 * but write only 4 (`b`), and both `HasNoAds` anchors verify 28/40 bytes but write 8.
 * The replacement stays inside the proven window, so no byte is ever written blind.
 *
 * ============================================================================
 * TWO-PHASE RESOLVE-THEN-WRITE (never leave the lib half-patched)
 * ============================================================================
 * [applyLibil2cppEdits] runs over a single open `RandomAccessFile` per ABI:
 *  1. **RESOLVE** — for *every* anchor of that ABI: bounds-check the window against
 *     the file length, read the window at the offset and compare it byte-for-byte
 *     with `verifyHex`, then stream the **whole file** and count occurrences of
 *     `verifyHex`. Nothing is written.
 *  2. **WRITE** — reached only if phase 1 passed for *all* anchors; seek and write
 *     `replacementHex` in place, then re-assert the file length.
 * Any failure in phase 1 throws [PatchException] naming the ABI, the RVA, the file
 * offset, the expected vs found window hex and the whole-file hit count — so a
 * moved/changed app build fails loudly with **zero bytes written**. Patches 2 and 3a
 * have multiple anchors each and must never apply "half" of them.
 *
 * Only small byte ranges are read/written; the uniqueness scan streams the lib
 * through a 1 MiB + overlap buffer instead of slurping ~90 MB into the heap.
 *
 * ============================================================================
 * ABI HANDLING (both libs present in the 0.0.3480 bundle)
 * ============================================================================
 * The `.apks` bundle carries `split_config.arm64_v8a.apk` **and**
 * `split_config.armeabi_v7a.apk`, so the merged APK contains both `libil2cpp.so`
 * builds. Every anchor set is therefore applied to its own ABI, and both are patched
 * in the same run; an APK is only ever emitted with *all* ABI edits of a patch
 * applied. `get("lib/<abi>/libil2cpp.so", true)` returns a `File` even when the path
 * is absent (Arsclib's ResourceCoder only `mkdirs()` the parent), so existence is
 * tested with `File.exists()` before reading.
 *
 * The ABI directory is the only switch: an ABI that the APK does not ship is skipped
 * with a log line (an APKPure-style arm64-only bundle is still patchable), but if
 * **none** of the supplied ABI sets resolve, the patch throws — the usual cause is
 * feeding morphe-cli the lib-less APKPure base-only APK instead of the `.apks`
 * bundle, and silently emitting an unpatchable APK would be worse than failing.
 * A lib that *is* present but does not match its anchors aborts the whole patch.
 *
 * ============================================================================
 * IN-PLACE EDIT ⇒ SAFE ROUND-TRIP
 * ============================================================================
 * Every edit is in place and same-length, so the lib's length never changes. On
 * encode, `detectFileChanges()` re-snapshots the extracted root tree
 * (lastModified + length); our write bumps the mtime, so the file lands in
 * `modifiedBinaryResources`, `getOtherResourceFiles(RAW_ONLY)` returns it, and
 * `ApkUtils.applyTo` overlays it into `rebuilt.apk`, which is then re-signed.
 * Magic Sort has no local signature check, no self-hash and no packer, so resigning
 * with `morphe.keystore` is sufficient (analysis/magicsort/possible-patches.md §2).
 */
internal fun ResourcePatchContext.applyLibil2cppEdits(
    patchName: String,
    anchorSets: List<AbiAnchorSet>,
) {
    require(anchorSets.isNotEmpty()) { "$patchName: no ABI anchor sets supplied" }

    val applied = mutableListOf<String>()
    val missing = mutableListOf<String>()

    for (set in anchorSets) {
        val lib = runCatching { get(set.libPath, true) }.getOrNull()
        if (lib == null || !lib.exists() || !lib.isFile) {
            missing += set.abi
            println("$patchName: ${set.libPath} not present in this APK — skipping ${set.abi}.")
            continue
        }
        applyEditsTo(patchName, set.abi, lib, set.anchors)
        applied += set.abi
    }

    if (applied.isEmpty()) {
        throw PatchException(
            "$patchName: none of the requested libil2cpp.so builds " +
                "(${anchorSets.joinToString { it.libPath }}) are present in this APK — " +
                "nothing was patched. Magic Sort 0.0.3480 ships arm64-v8a + armeabi-v7a; " +
                "patch the Magic-Sort_0.0.3480_apkcube.apks split bundle (base + " +
                "split_config.arm64_v8a + split_config.armeabi_v7a), not the lib-less " +
                "APKPure base-only APK.",
        )
    }

    println(
        "$patchName: patched ABI(s) ${applied.joinToString()}" +
            if (missing.isEmpty()) "." else "; not shipped: ${missing.joinToString()}.",
    )
}

/**
 * Two-phase (resolve-then-write) application of every [anchor] in [lib].
 *
 * @param patchName patch name, used as the prefix of every log/exception message.
 * @param abi ABI of [lib], used in every log/exception message.
 * @param lib extracted `libil2cpp.so` on disk (opened `"rw"`, same length after).
 * @param anchors every edit to apply — all are validated before any is written.
 * @throws PatchException on a bounds violation, a window mismatch, or a whole-file
 *   hit count that is not exactly 1. Nothing is written in that case.
 */
private fun applyEditsTo(
    patchName: String,
    abi: String,
    lib: File,
    anchors: List<NativeAnchor>,
) {
    RandomAccessFile(lib, "rw").use { raf ->
        val size = raf.length()
        println(
            "$patchName [$abi]: ${lib.name} is $size bytes; resolving " +
                "${anchors.size} edit(s) before writing any.",
        )

        // ── PHASE 1 — resolve + validate ALL anchors, write NOTHING ─────────────
        for (anchor in anchors) {
            val end = anchor.fileOffset + anchor.verifyBytes.size
            if (anchor.fileOffset < 0 || end > size) {
                throw PatchException(
                    "$patchName [$abi]: ${anchor.label} — verify window " +
                        "0x${anchor.fileOffset.toString(16)}..0x${end.toString(16)} " +
                        "(${anchor.verifyBytes.size} B) is outside ${lib.name} " +
                        "(size=$size). Wrong file offset for this build?",
                )
            }
            val actual = readAt(raf, anchor.fileOffset, anchor.verifyBytes.size)
            if (!actual.contentEquals(anchor.verifyBytes)) {
                throw PatchException(
                    "$patchName [$abi]: ${anchor.label} — verify window mismatch at " +
                        "RVA 0x${anchor.rva.toString(16)} (file " +
                        "0x${anchor.fileOffset.toString(16)}): expected " +
                        "${toHex(anchor.verifyBytes)} but found ${toHex(actual)}. " +
                        "libil2cpp.so layout changed — unsupported app version or wrong ABI? (ABI $abi)",
                )
            }
            val hits = countOccurrences(raf, anchor.verifyBytes)
            if (hits != 1) {
                throw PatchException(
                    "$patchName [$abi]: ${anchor.label} — verify window " +
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
                "$patchName [$abi]: RVA 0x${anchor.rva.toString(16)} (file " +
                    "0x${anchor.fileOffset.toString(16)}): wrote " +
                    "${toHex(anchor.replacementBytes)} over ${anchor.verifyBytes.size} " +
                    "uniquely-verified bytes — ${anchor.label}",
            )
        }
        check(raf.length() == size) {
            "$patchName [$abi]: ${lib.name} length changed during patching " +
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

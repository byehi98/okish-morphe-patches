package app.csrracing.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One guarded native edit inside CSR Racing's shipped `libil2cpp.so`.
 *
 * `verifyHex` and `replacementHex` are **independent axes**: the verify window is
 * how the anchor is *proven unique*, the replacement is how many bytes actually
 * change. The only relationship enforced is that the replacement fits inside the
 * verified window, so every byte that can be touched is covered by the uniqueness
 * proof.
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property rva virtual address in the shipped lib (what Il2CppDumper/disassembly shows).
 * @property fileOffset exact on-disk offset (for both ABIs here: **equal to `rva`** — see KDoc below).
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
            "$label: verify window is only ${verifyBytes.size} B — IL2CPP " +
                "prologues/branches in this 26 MB lib need at least " +
                "$MIN_VERIFY_WINDOW B to be unambiguous (see NativeBytePatch KDoc)"
        }
        require(replacementBytes.size <= verifyBytes.size) {
            "$label: replacement is ${replacementBytes.size} B but the verify window is " +
                "${verifyBytes.size} B — the write would escape the uniqueness proof"
        }
    }
}

/**
 * One ABI's `libil2cpp.so` plus the anchors valid for **that** binary.
 *
 * CSR ships the same IL2CPP metadata twice, once compiled for AArch64 and once for ARMv7.
 * The method set, order and semantics match; the addresses, encodings and prologues do not.
 * Anchors are therefore never shared between targets.
 *
 * @property path in-APK path of the lib, e.g. `lib/armeabi-v7a/libil2cpp.so`.
 * @property anchors edits for this lib only.
 */
internal class LibTarget(
    val path: String,
    val anchors: List<NativeAnchor>,
) {
    init {
        require(anchors.isNotEmpty()) { "$path: no native anchors supplied" }
    }
}

/** Smallest accepted verify window; anything shorter is ambiguous in a 20–26 MB lib. */
private const val MIN_VERIFY_WINDOW = 24

/** Streaming granularity for the whole-file uniqueness scan (plus needle overlap). */
private const val SCAN_CHUNK = 1 shl 20

/**
 * ============================================================================
 * WHAT THIS EDITS, AND WHY A `rawResourcePatch` IS THE ONLY WAY
 * ============================================================================
 * CSR Racing 5.1.5 is Unity **2017.4.40f1** with the **IL2CPP** scripting
 * backend (metadata v24, plaintext, full names intact). The entire economy —
 * gold/cash balances, spend paths, fuel, upgrades, entitlements — is C#
 * compiled to native AArch64 inside `lib/arm64-v8a/libil2cpp.so`
 * (26,330,608 B on 5.1.5). There is no meaningful smali to fingerprint for
 * those behaviours: the DEX is an R8-minified 2.18 MiB shell plus the Play
 * Billing bridge.
 *
 * A `bytecodePatch` physically cannot do this: its context is
 * `BytecodePatchContext`, and the file API (`get(path, uncompress)`) exists only
 * on `ResourcePatchContext`. A `rawResourcePatch` anywhere in the patch graph
 * forces `ResourceMode.RAW_ONLY`, which is what makes the raw-extracted
 * `root/lib/arm64-v8a/libil2cpp.so` exist on disk and be resolvable via `get(...)`.
 *
 * ============================================================================
 * RVA == FILE OFFSET IN BOTH LIBS
 * ============================================================================
 * `readelf -lW libil2cpp.so` shows the **executable** `PT_LOAD` at
 * `Offset 0x0 / VirtAddr 0x0` in both binaries — arm64 (26,330,608 B) and armv7
 * (21,354,076 B) — so the first segment's file offset equals its virtual address.
 * Every anchor used by these patches lives in that segment, hence
 * `fileOffset == rva` exactly in both cases. The fields are kept separate only
 * so the intent and every log line show both numbers.
 *
 * ============================================================================
 * BOTH ABIs ARE PATCHED
 * ============================================================================
 * CSR 5.1.5 is a fat APK: `lib/arm64-v8a/libil2cpp.so` **and**
 * `lib/armeabi-v7a/libil2cpp.so` are both present. IL2CPP compiles the same
 * metadata separately per ABI, so each lib has its own addresses, its own
 * machine code (AArch64 vs. **ARM state**, not Thumb) and its own prologues.
 * Every [LibTarget] therefore carries anchors that are valid for exactly one
 * lib, and [applyLibil2cppEdits] applies each target that exists in the APK.
 *
 * Consequences:
 *  - fat APK → both libs edited, so 32-bit and 64-bit devices behave identically;
 *  - arm64-only APK → the armv7 target is skipped (logged), arm64 is patched;
 *  - armv7-only APK → the arm64 target is skipped (logged), armv7 is patched;
 *  - neither lib → [PatchException], because a patch that edits nothing is a lie.
 *
 * ============================================================================
 * WHY THE VERIFY WINDOWS ARE 24 B ON ARM64 AND 48–56 B ON ARMV7
 * ============================================================================
 * A 4-byte `ret` or an 8–12-byte getter stub is worthless as a locator: blind
 * searching a 20–26 MB IL2CPP lib for it would hit thousands of unrelated
 * functions. Every anchor therefore carries the **actual instruction sequence
 * at the edit site**, and this engine additionally requires that window to
 * occur **exactly once in the entire `.so`** — 0 hits means the offset is wrong,
 * >1 means the window is ambiguous, and both abort the patch.
 *
 * The AArch64 prologues are distinctive enough at 24 B. The ARMv7 ones are not:
 * the getters share `push {r4,r5,r6,sl,fp,lr}; add fp,sp,#0x10; mov r4,r0; …`
 * and the exact 24 B window occurs 2–11 times per lib. The armv7 windows are
 * therefore grown until unique, which lands at 48 B (getters), 52 B
 * (`SpendGold`) and 56 B (`SpendCash`).
 *
 * The verify/replacement split matters: the getter anchors verify the whole
 * window but write 12 B, and the spend anchors write 4 (`bx lr`/`ret`). The
 * replacement stays inside the proven window, so no byte is ever written blind.
 *
 * The verify/replacement split matters: the getter anchors verify 24 B but
 * write 12, and the spend anchors verify 24 B but write 4 (`ret`). The
 * replacement stays inside the proven window, so no byte is ever written blind.
 *
 * ============================================================================
 * TWO-PHASE RESOLVE-THEN-WRITE (never leave the lib half-patched)
 * ============================================================================
 * [applyLibil2cppEdits] runs over a single open `RandomAccessFile`:
 *  1. **RESOLVE** — for *every* anchor: bounds-check the window against the file
 *     length, read the window at the offset and compare it byte-for-byte with
 *     `verifyHex`, then stream the **whole file** and count occurrences of
 *     `verifyHex`. Nothing is written.
 *  2. **WRITE** — reached only if phase 1 passed for *all* anchors; seek and write
 *     `replacementHex` in place, then re-assert the file length.
 * Any failure in phase 1 throws [PatchException] naming the RVA, the file offset, the
 * expected vs found window hex and the whole-file hit count — so a moved/changed app
 * build fails loudly with **zero bytes written**. Each patch has two anchors and
 * must never apply "half" of them.
 *
 * Only small byte ranges are read/written; the uniqueness scan streams the lib
 * through a 1 MiB + overlap buffer instead of slurping 26 MB into the heap.
 *
 * ============================================================================
 * INPUT / ABI HANDLING
 * ============================================================================
 * `get(path, true)` returns a `File` even when the path is absent (Arsclib's
 * ResourceCoder only `mkdirs()` the parent), so existence is tested with
 * `File.exists()` before reading. Targets whose lib is absent are skipped with a
 * log line rather than failing, so a single-ABI APK still patches. If **no**
 * target is present the patch throws: the patchable input is the **fat base APK**
 * (`com.naturalmotion.csrracing_5.1.5-20251202_minAPI21(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk`),
 * which must contain at least one of `lib/arm64-v8a/libil2cpp.so` or
 * `lib/armeabi-v7a/libil2cpp.so`. Feeding morphe-cli the `.apkm` bundle or a
 * lib-less base-only APK would silently emit an unpatchable APK, which is worse
 * than failing.
 *
 * ============================================================================
 * IN-PLACE EDIT ⇒ SAFE ROUND-TRIP
 * ============================================================================
 * Every edit is in place and same-length, so the lib's length never changes. On
 * encode, `detectFileChanges()` re-snapshots the extracted root tree
 * (lastModified + length); our write bumps the mtime, so the file lands in
 * `modifiedBinaryResources`, `getOtherResourceFiles(RAW_ONLY)` returns it, and
 * `ApkUtils.applyTo` overlays it into `rebuilt.apk`, which is then re-signed.
 * CSR 5.1.5 has no packer, no integrity attestation and no self-hash, and no
 * active signature reject path was found, so resigning with `morphe.keystore` is
 * sufficient (analysis/csr/possible-patches.md §1 and §7.4).
 */
internal fun ResourcePatchContext.applyLibil2cppEdits(
    patchName: String,
    targets: List<LibTarget>,
) {
    require(targets.isNotEmpty()) { "$patchName: no native targets supplied" }

    var applied = 0
    val skipped = mutableListOf<String>()
    for (target in targets) {
        val lib = runCatching { get(target.path, true) }.getOrNull()
        if (lib == null || !lib.exists() || !lib.isFile) {
            skipped += target.path
            println("$patchName: ${target.path} is not in this APK — skipping that ABI.")
            continue
        }
        applyEditsTo(patchName, lib, target.anchors)
        applied++
    }

    if (applied == 0) {
        throw PatchException(
            "$patchName: none of the native targets were present ($skipped) — nothing was " +
                "patched. CSR Racing 5.1.5 must be patched from the fat base APK " +
                "com.naturalmotion.csrracing_5.1.5-20251202_minAPI21(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk " +
                "(it contains lib/arm64-v8a/libil2cpp.so and lib/armeabi-v7a/libil2cpp.so), " +
                "not from the .apkm bundle or a lib-less base-only APK.",
        )
    }
    if (skipped.isNotEmpty()) {
        println("$patchName: patched $applied of ${targets.size} ABI target(s); skipped $skipped.")
    }
}

/**
 * Two-phase (resolve-then-write) application of every [anchor] in [lib].
 *
 * @param patchName patch name, used as the prefix of every log/exception message.
 * @param lib extracted `libil2cpp.so` on disk (opened `"rw"`, same length after).
 * @param anchors every edit to apply — all are validated before any is written.
 * @throws PatchException on a bounds violation, a window mismatch, or a whole-file
 *   hit count that is not exactly 1. Nothing is written in that case.
 */
private fun applyEditsTo(
    patchName: String,
    lib: File,
    anchors: List<NativeAnchor>,
) {
    RandomAccessFile(lib, "rw").use { raf ->
        val size = raf.length()
        println(
            "$patchName: ${lib.name} is $size bytes; resolving " +
                "${anchors.size} edit(s) before writing any.",
        )

        // ── PHASE 1 — resolve + validate ALL anchors, write NOTHING ─────────────
        for (anchor in anchors) {
            val end = anchor.fileOffset + anchor.verifyBytes.size
            if (anchor.fileOffset < 0 || end > size) {
                throw PatchException(
                    "$patchName: ${anchor.label} — verify window " +
                        "0x${anchor.fileOffset.toString(16)}..0x${end.toString(16)} " +
                        "(${anchor.verifyBytes.size} B) is outside ${lib.name} " +
                        "(size=$size). Wrong file offset for this build?",
                )
            }
            val actual = readAt(raf, anchor.fileOffset, anchor.verifyBytes.size)
            if (!actual.contentEquals(anchor.verifyBytes)) {
                throw PatchException(
                    "$patchName: ${anchor.label} — verify window mismatch at " +
                        "RVA 0x${anchor.rva.toString(16)} (file " +
                        "0x${anchor.fileOffset.toString(16)}): expected " +
                        "${toHex(anchor.verifyBytes)} but found ${toHex(actual)}. " +
                        "libil2cpp.so layout changed — unsupported app version?",
                )
            }
            val hits = countOccurrences(raf, anchor.verifyBytes)
            if (hits != 1) {
                throw PatchException(
                    "$patchName: ${anchor.label} — verify window " +
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
                "$patchName: RVA 0x${anchor.rva.toString(16)} (file " +
                    "0x${anchor.fileOffset.toString(16)}): wrote " +
                    "${toHex(anchor.replacementBytes)} over ${anchor.verifyBytes.size} " +
                    "uniquely-verified bytes — ${anchor.label}",
            )
        }
        check(raf.length() == size) {
            "$patchName: ${lib.name} length changed during patching " +
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

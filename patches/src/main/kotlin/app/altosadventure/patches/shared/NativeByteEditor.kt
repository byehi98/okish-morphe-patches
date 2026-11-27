package app.altosadventure.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One guarded native edit inside a shipped `libil2cpp.so`.
 *
 * `verifyHex` is how the anchor is *proven unique*; `replacementHex` is how many
 * bytes actually change. The only relationship enforced is that the replacement
 * fits inside the verified window, so every byte that can be touched is covered
 * by the uniqueness proof.
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property rva virtual address in the shipped lib (what disassembly shows).
 * @property fileOffset exact on-disk offset (arm64: `rva - 0x4000`; arm32: `rva`).
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

private const val LIBIL2CPP = "libil2cpp.so"

/** Streaming granularity for the whole-file uniqueness scan (plus needle overlap). */
private const val SCAN_CHUNK = 1 shl 20

/**
 * Applies [targets] to the extracted `libil2cpp.so` of every ABI that ships one.
 *
 * Alto's Adventure 1.8.34 is Unity 6 / IL2CPP with metadata v39. All game logic —
 * including the coin balance — lives in `libil2cpp.so`; there is no DEX code for it.
 * These edits are **in place and static**: no companion `.so`, no `loadLibrary`
 * trigger, no code executing at launch, so there is no new crash vector.
 *
 * An ABI whose lib is absent is skipped with a log line (e.g. a `--striplibs` run or
 * a single-ABI build). If *no* targeted ABI is present at all, that is an error.
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
            println("$patchName: $path is not present in this APK — skipping the ${target.abi} edits.")
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
 * Two-phase (resolve-then-write) application of every [anchor] in one [lib]:
 * all anchors are bounds-checked, byte-compared and proven unique before the first
 * write, so a moved/changed build fails loudly with **zero bytes written**.
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
                        "${lib.name} ($size bytes); exactly 1 is required.",
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

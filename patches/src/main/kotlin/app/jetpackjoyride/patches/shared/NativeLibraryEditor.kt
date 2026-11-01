package app.jetpackjoyride.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.io.RandomAccessFile

/**
 * One guarded native edit inside a shipped game library (`lib/<abi>/libmortargame.so`).
 *
 * [verifyHex] and [replacementHex] are independent axes: the verify window is how the
 * anchor is proven unique, the replacement is how many bytes actually change. The only
 * relationship enforced is that the replacement fits inside the verified window, so every
 * byte that can be touched is covered by the uniqueness proof.
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property rva virtual address in the shipped lib (what disassembly shows).
 * @property fileOffset exact on-disk offset in the shipped lib.
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

/** Streaming granularity for the whole-file uniqueness scan (plus needle overlap). */
private const val SCAN_CHUNK = 1 shl 20

/**
 * ============================================================================
 * WHY A `rawResourcePatch` IS THE ONLY WAY
 * ============================================================================
 * Jetpack Joyride's monetisation / event gating lives in **native C++**
 * (`libmortargame.so`, Halfbrick's "Mortar" engine), not in DEX. The app's Java
 * layer is only the Play Billing / ads / JNI glue — there is no smali method that
 * decides whether the player is an "IAP cheat" or whether an event may be entered.
 * A `bytecodePatch` therefore physically cannot reach the logic this patch edits.
 *
 * A `rawResourcePatch` sitting anywhere in the patch graph forces
 * `ResourceMode.RAW_ONLY`, which is what makes the raw-extracted
 * `lib/<abi>/libmortargame.so` exist on disk and be resolvable via `get(...)`
 * (the file API exists only on [ResourcePatchContext]).
 *
 * ============================================================================
 * RVA → FILE OFFSET
 * ============================================================================
 * All four shipped libs were checked with `readelf -lW`: the RX `PT_LOAD` has
 * `p_offset = 0` and `p_vaddr = 0`, i.e. **file offset == RVA** for every ABI
 * (arm64-v8a, armeabi-v7a, x86, x86_64). No delta is applied, and every anchor
 * carries its own measured offset — an ABI's offsets/bytes are never reused for
 * another ABI.
 *
 * ============================================================================
 * TWO-PHASE RESOLVE-THEN-WRITE (never leave the lib half-patched)
 * ============================================================================
 * For each ABI: (1) bounds-check every window, compare the on-disk bytes with
 * [NativeAnchor.verifyBytes], and stream the whole file counting occurrences of
 * the window — nothing is written; (2) only if all anchors passed, seek and write
 * every replacement in place. Any failure throws [PatchException] naming the ABI,
 * RVA, file offset, expected vs found bytes and the whole-file hit count, with
 * **zero bytes written**.
 *
 * An ABI whose lib is absent from the APK is skipped with a log line (a
 * `--striplibs` run legitimately removes ABIs); a hard error is thrown only when
 * none of the targeted ABIs is present, or when a present ABI's window does not
 * match (different build) or is not unique.
 */
internal fun ResourcePatchContext.applyNativeEdits(
    patchName: String,
    libName: String,
    targets: List<AbiEdits>,
) {
    var patchedAbis = 0
    for (target in targets) {
        val path = "lib/${target.abi}/$libName"
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
                "(${targets.joinToString(", ") { it.abi }}) carries a $libName in this " +
                "APK — nothing was patched. Unsupported app build?",
        )
    }
}

private fun applyEditsTo(
    patchName: String,
    lib: File,
    abi: String,
    anchors: List<NativeAnchor>,
) {
    require(anchors.isNotEmpty()) { "$patchName ($abi): no anchors supplied" }
    RandomAccessFile(lib, "rw").use { raf ->
        val size = raf.length()
        println("$patchName ($abi): ${lib.name} is $size bytes; resolving ${anchors.size} edit(s) before writing any.")

        // PHASE 1 — resolve + validate ALL anchors, write NOTHING.
        for (anchor in anchors) {
            val end = anchor.fileOffset + anchor.verifyBytes.size
            if (anchor.fileOffset < 0 || end > size) {
                throw PatchException(
                    "$patchName ($abi): ${anchor.label} — verify window " +
                        "0x${anchor.fileOffset.toString(16)}..0x${end.toString(16)} " +
                        "(${anchor.verifyBytes.size} B) is outside ${lib.name} (size=$size). " +
                        "Wrong file offset for this build?",
                )
            }
            val actual = readAt(raf, anchor.fileOffset, anchor.verifyBytes.size)
            if (!actual.contentEquals(anchor.verifyBytes)) {
                throw PatchException(
                    "$patchName ($abi): ${anchor.label} — verify window mismatch at " +
                        "RVA 0x${anchor.rva.toString(16)} (file 0x${anchor.fileOffset.toString(16)}): " +
                        "expected ${toHex(anchor.verifyBytes)} but found ${toHex(actual)}. " +
                        "${lib.name} layout changed — unsupported app version?",
                )
            }
            val hits = countOccurrences(raf, anchor.verifyBytes)
            if (hits != 1) {
                throw PatchException(
                    "$patchName ($abi): ${anchor.label} — verify window " +
                        "${toHex(anchor.verifyBytes)} at RVA 0x${anchor.rva.toString(16)} " +
                        "(file 0x${anchor.fileOffset.toString(16)}) occurs $hits time(s) in " +
                        "${lib.name} ($size bytes); exactly 1 is required. A 0-hit window means " +
                        "the offset is wrong, >1 means the window is ambiguous — refusing to patch.",
                )
            }
        }

        // PHASE 2 — all anchors validated, so the write cannot half-apply.
        for (anchor in anchors) {
            raf.seek(anchor.fileOffset)
            raf.write(anchor.replacementBytes)
            println(
                "$patchName ($abi): RVA 0x${anchor.rva.toString(16)} (file 0x${anchor.fileOffset.toString(16)}): " +
                    "wrote ${toHex(anchor.replacementBytes)} over ${anchor.verifyBytes.size} " +
                    "uniquely-verified bytes — ${anchor.label}",
            )
        }
        check(raf.length() == size) {
            "$patchName ($abi): ${lib.name} length changed during patching " +
                "($size -> ${raf.length()}) — the edit must be in place"
        }
    }
}

private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    raf.seek(offset)
    raf.readFully(out)
    return out
}

/** Counts occurrences of [needle] across the whole file, streaming in chunks. */
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

package app.injustice.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * One in-place edit of a native library.
 *
 * The library is 50 MB of AArch64, so a bare 4-byte word is worthless as a locator. Every
 * site therefore carries the surrounding instruction window and the engine requires that
 * window to occur **exactly once in the whole file**: 0 hits means the app build moved,
 * >1 means the window is ambiguous, and both abort before a single byte is written.
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property verifyHex file bytes expected at the site (little-endian byte order, i.e. the
 *   order they appear in the `.so` on disk).
 * @property replacementHex bytes written at the site; must fit inside [verifyHex] so every
 *   byte that can be touched is covered by the uniqueness proof.
 */
internal class NativeSite(
    val label: String,
    val verifyHex: String,
    replacementHex: String,
) {
    val verifyBytes: ByteArray = hexBytes(verifyHex)
    val replacementBytes: ByteArray = hexBytes(replacementHex)

    init {
        require(verifyBytes.isNotEmpty()) { "$label: empty verify pattern" }
        require(replacementBytes.isNotEmpty()) { "$label: empty replacement" }
        require(replacementBytes.size <= verifyBytes.size) {
            "$label: replacement is ${replacementBytes.size} B but the verify window is " +
                "${verifyBytes.size} B — the write would escape the uniqueness proof"
        }
    }
}

/**
 * Applies [sites] to [libPath] inside the APK.
 *
 * Resolve-then-write: every site is located and uniqueness-checked first, then all
 * replacements are written in place. A mismatch therefore aborts with zero bytes written
 * instead of leaving a half-patched library behind.
 */
internal fun ResourcePatchContext.applyNativeSites(
    patchName: String,
    libPath: String,
    sites: List<NativeSite>,
) {
    require(sites.isNotEmpty()) { "$patchName: no native sites supplied" }

    val lib = runCatching { get(libPath, true) }.getOrNull()
    if (lib == null || !lib.exists() || !lib.isFile) {
        throw PatchException(
            "$patchName: $libPath is not present in the APK — nothing was patched. " +
                "Feed the full Injustice 3.5.1 .xapk (base + OBBs), not a lib-less base-only APK.",
        )
    }

    val bytes = lib.readBytes()
    println("$patchName: ${lib.name} is ${bytes.size} bytes; resolving ${sites.size} site(s).")

    val resolved = sites.map { site -> site to indexOfUnique(patchName, lib, bytes, site) }

    for ((site, offset) in resolved) {
        site.replacementBytes.copyInto(bytes, offset, 0, site.replacementBytes.size)
        println(
            "$patchName: ${site.label} @ file 0x${offset.toString(16)} — " +
                "wrote ${site.replacementBytes.size} B over a ${site.verifyBytes.size} B " +
                "uniquely-verified window.",
        )
    }

    lib.writeBytes(bytes)
    check(lib.length() == bytes.size.toLong()) {
        "$patchName: ${lib.name} length changed during patching — the edit must be in place."
    }
}

/** Returns the single offset of [site]'s verify window, or throws. */
private fun indexOfUnique(
    patchName: String,
    lib: java.io.File,
    haystack: ByteArray,
    site: NativeSite,
): Int {
    var first = -1
    var hits = 0
    val last = haystack.size - site.verifyBytes.size
    var i = 0
    while (i <= last) {
        if (haystack[i] == site.verifyBytes[0] && regionMatches(haystack, i, site.verifyBytes)) {
            hits++
            if (first < 0) first = i
            if (hits > 1) break
        }
        i++
    }

    if (hits != 1) {
        throw PatchException(
            "$patchName: ${site.label} — verify window ${site.verifyHex} occurs " +
                "$hits time(s) in ${lib.name} (${haystack.size} bytes); exactly 1 is required. " +
                "0 hits means this build is not Injustice 3.5.1, >1 means the window is " +
                "ambiguous — refusing to patch.",
        )
    }
    return first
}

private fun regionMatches(buffer: ByteArray, offset: Int, needle: ByteArray): Boolean {
    for (k in needle.indices) {
        if (buffer[offset + k] != needle[k]) return false
    }
    return true
}

/** Parses a plain hex string (no separators, optional `0x`) into bytes. */
internal fun hexBytes(hex: String): ByteArray {
    val clean = hex.removePrefix("0x").replace(" ", "").replace("\n", "").replace("\t", "")
    require(clean.length % 2 == 0) { "hex string must have an even length: '$hex'" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

package app.aliensdrivemecrazy.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Aliens Drive Me Crazy is a Unity **IL2CPP** title: every behaviour these patches change
 * (interstitials, rewarded videos, coin save reads) lives in native code inside
 * `lib/<abi>/libil2cpp.so`. The DEX layer is only ad-SDK glue, so a `bytecodePatch` cannot
 * reach any of it — a `rawResourcePatch` is required, which also forces
 * `ResourceMode.RAW_ONLY` so the extracted `root/lib/<abi>/libil2cpp.so` exists on disk and
 * is resolvable through [ResourcePatchContext.get].
 *
 * 3.2.11 (`versionCode 52`) ships **two** `libil2cpp.so` builds in the `.xapk`
 * (`config.arm64_v8a.apk` 56,182,680 B and `config.armeabi_v7a.apk` 47,426,572 B). They
 * share the IL2CPP metadata but are separate compilations, so every function sits at a
 * different offset and uses a different instruction set (A64 vs A32), and — importantly —
 * IL2CPP lays out class fields per ABI (`MyIronSource.JakaNagroda` is `0x24` on
 * arm64-v8a but `0x14` on armeabi-v7a). Each ABI therefore gets its own anchor list.
 * A `libil2cpp.so` that the APK does not ship is skipped with a log line so a single-ABI
 * bundle stays patchable; if *no* supplied ABI resolves the patch aborts, because silently
 * shipping an unpatchable APK is worse than failing.
 *
 * ### Why a full verify window instead of a raw offset
 *
 * A 4-byte `ret`/`bx lr` or an 8-byte `mov w0,#0 ; ret` is worthless as a locator in a
 * 56 MB library. Every [NativeSite] carries the real instruction sequence around the edit
 * and this engine additionally requires that sequence to occur **exactly once in the whole
 * file** — 0 hits means the app build moved, >1 means the window is ambiguous, and both
 * abort before a single byte is written. All anchors were verified against the shipped
 * 3.2.11 libs (1 hit each).
 *
 * @property label human-readable site description, echoed into logs and exceptions.
 * @property verifyHex bytes expected at the site; must occur exactly once in the lib.
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

/** The native edits for one ABI, keyed by the split's `lib/` directory name. */
internal class AbiSites(
    val abi: String,
    val sites: List<NativeSite>,
) {
    init {
        require(abi.isNotBlank()) { "empty ABI name" }
        require(sites.isNotEmpty()) { "$abi: no native sites supplied" }
    }

    val libPath: String get() = "lib/$abi/libil2cpp.so"
}

/**
 * Applies [abiSites] to every shipped ABI (arm64-v8a **and** armeabi-v7a), so an APK is
 * only ever emitted with the edits of a patch applied to *all* of its native libraries.
 *
 * Resolve-then-write: for one ABI every site is located and uniqueness-checked first, then
 * all replacements are written in place. A mismatch therefore aborts with zero bytes
 * written instead of leaving a half-patched library behind.
 */
internal fun ResourcePatchContext.applyNativeSites(
    patchName: String,
    abiSites: List<AbiSites>,
) {
    require(abiSites.isNotEmpty()) { "$patchName: no ABI site sets supplied" }

    val applied = mutableListOf<String>()
    val missing = mutableListOf<String>()

    for (set in abiSites) {
        val lib = runCatching { get(set.libPath, true) }.getOrNull()
        if (lib == null || !lib.exists() || !lib.isFile) {
            missing += set.abi
            println("$patchName: ${set.libPath} not shipped in this APK — skipping ${set.abi}.")
            continue
        }
        applyToLib(patchName, set, lib)
        applied += set.abi
    }

    if (applied.isEmpty()) {
        throw PatchException(
            "$patchName: neither lib/arm64-v8a/libil2cpp.so nor lib/armeabi-v7a/libil2cpp.so " +
                "is present in this APK — nothing was patched. Feed the full " +
                "Aliens-Drive-Me-Crazy 3.2.11 .xapk (base + config.arm64_v8a + " +
                "config.armeabi_v7a) to the CLI, not a lib-less base-only APK.",
        )
    }

    println(
        "$patchName: patched ABI(s) ${applied.joinToString()}" +
            if (missing.isEmpty()) "." else "; not shipped: ${missing.joinToString()}.",
    )
}

private fun applyToLib(patchName: String, set: AbiSites, lib: java.io.File) {
    val bytes = lib.readBytes()
    println("$patchName [${set.abi}]: ${lib.name} is ${bytes.size} bytes; resolving ${set.sites.size} site(s).")

    // ── resolve: locate + prove uniqueness for every site before writing anything ──
    val resolved = set.sites.map { site -> site to indexOfUnique(patchName, set.abi, lib, bytes, site) }

    // ── write: all sites validated, so this cannot half-apply ──
    for ((site, offset) in resolved) {
        site.replacementBytes.copyInto(bytes, offset, 0, site.replacementBytes.size)
        println(
            "$patchName [${set.abi}]: ${site.label} @ file 0x${offset.toString(16)} — " +
                "wrote ${site.replacementBytes.size} B over a ${site.verifyBytes.size} B " +
                "uniquely-verified window.",
        )
    }

    lib.writeBytes(bytes)
    check(lib.length() == bytes.size.toLong()) {
        "$patchName [${set.abi}]: ${lib.name} length changed during patching — the edit must be in place."
    }
}

/** Returns the single offset of [site]'s verify window, or throws. */
private fun indexOfUnique(
    patchName: String,
    abi: String,
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
            "$patchName [$abi]: ${site.label} — verify window ${site.verifyHex} occurs " +
                "$hits time(s) in ${lib.name} (${haystack.size} bytes); exactly 1 is " +
                "required. 0 hits means this app build is not 3.2.11 (or the wrong ABI is " +
                "being patched), >1 means the window is ambiguous — refusing to patch.",
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

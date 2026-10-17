package app.fruitninjafree.patches.shared

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
 * proof. When a site sits in the middle of the window, the replacement simply
 * repeats the unchanged surrounding bytes verbatim and swaps only the edited
 * instruction(s) — the write always starts at [fileOffset] (the window start).
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
 * Fruit Ninja Free 3.97.9 (versionCode 88485200) is Unity **2022.3.62f2** with
 * the **IL2CPP** scripting backend. The purchase pipeline —
 * `PurchaseManager.ValidateAndProcessIAP → ProcessGooglePlayIAP` → PlayFab
 * `ValidateGooglePlayPurchase` **server-side** receipt validation → grant — is
 * C# compiled to native ARM code inside `lib/<abi>/libil2cpp.so`
 * (arm64-v8a **71,750,584 B**, armeabi-v7a **61,685,188 B**, both shipped;
 * BuildID arm64 `8b779e1592f5d19309b9fb829b854492edea84ee`). The app's real
 * grant path runs `ValidateAndProcessIAP → ProcessGooglePlayIAP → PlayFab
 * ValidateGooglePlayPurchase` — a **server-side** check that verifies the Google
 * `purchaseToken` against the Play Developer API, so no Java-fabricated receipt
 * can pass it (`analysis/com.halfbrick.fruitninjafree/notes/iap-validation-debug.md`).
 * That is half of why the free-IAP grant needs a **static in-place byte edit of
 * the shipped lib**; the other half is that there is no smali that can change
 * what the C# callback *does*. The shipped, working design is TWO cooperating
 * halves (billing patch `billing/FruitNinjaFreeIapPatch.kt`):
 * * a **Java** `launchBillingFlow` forge delivers a fake purchase Unity IAP
 *   hands to the game (a Java hook IS possible and is half the patch — the
 *   "Java route is dead" verdict only means a Java hook alone cannot pass the
 *   server check), and
 * * **this** native half redirects `ProcessGooglePlayIAP` into the game's own
 *   built-in `ProcessFakeIAP` grant path, skipping that server check.
 * Neither works alone; together they make the store free.
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
 *  - the shipped lib's `.text` is plaintext on disk and standard-stripped (all
 *    `il2cpp_*` exports are present), and
 *  - there is **no anti-tamper of any kind** to defeat — pairip is absent in both
 *    variants, no VM/protector, no classic LVL gate, no Java anti-tamper layer.
 *    The only real gate is **Play Integrity**, which gates nothing relevant to
 *    static file edits (the APK is re-signed as one unit and that is sufficient).
 *    (`analysis/com.halfbrick.fruitninjafree/notes/possible-patches.md`,
 *    `license-integrity.md`.)
 * This also sidesteps the ubisoftpop packed-lib timing lesson outright: nothing is
 * touched at runtime, before or after Unity's native init.
 *
 * ============================================================================
 * RVA → FILE OFFSET (re-derived, not assumed)
 * ============================================================================
 * ```
 * arm64-v8a  RX LOAD segment: file_offset = RVA − 0x4000
 *      (verified: LOAD2 vaddr 0x18BB3B4 ↔ offset 0x18B73B4; dump.cs `Offset:`
 *       column agrees for every method used here, e.g. RVA 0x1E9F084 →
 *       Offset 0x1E9B084)
 * armeabi-v7a NOT PATCHED — see "arm64-only" below; if v7 sites are ever
 *      verified, its LOAD layout must be re-read with `readelf -lW` first
 *      (conventionally delta 0, but never assumed).
 * ```
 *
 * ============================================================================
 * arm64-ONLY — WHY THE v7a SPLIT STAYS STOCK
 * ============================================================================
 * The APKM ships two ABI splits (`requiredSplitTypes="base__abi,base__density"`;
 * recon.md), but **every byte site below was measured on the arm64-v8a lib only**
 * (llvm-objdump + raw-byte re-reads against
 * `analysis/com.halfbrick.fruitninjafree/il2cpp/libil2cpp.so`). The v7a lib is a
 * separate build with its own code layout — RVAs and even instruction encodings
 * (A32 vs Thumb-2) cannot be extrapolated from arm64. So [applyNativeEdits] is
 * given **only** the `arm64-v8a` target; the v7a lib ships unpatched (an armv7
 * device simply runs the vanilla game). Device tests target arm64 (Waydroid).
 *
 * ============================================================================
 * WHY EVERY ANCHOR NEEDS A WHOLE-FILE-UNIQUE VERIFY WINDOW
 * ============================================================================
 * The single most important implementation rule, and a direct lesson from Dead
 * Target (12-byte anchors measured 313/418/418/40/6 hits in its own lib), Head
 * Basketball (standard IL2CPP prologues: 24,078 hits), and the P2 site in THIS
 * app. Measured on this 71.7 MB lib with a whole-file scan (non-overlapping
 * count, mirroring this engine):
 * ```
 *   P2 ProcessGooglePlayIAP 12 B prologue alone (str x30/stp/stp — a
 *     standard il2cpp frame, what ProcessGooglePlayIAP starts with)   -> 7,481 hits  ✗
 *   ...same site + 12 B more (adds stp x20,x19 + the two site-specific
 *     adrp's) -> 1 hit ✓   ← the shipped P2 window (ProcessFakeIapRedirect.kt)
 * ```
 * (Earlier rounds measured 32 B windows for a Target 3 `cbz`/Target 2 gate that
 * needed a site-specific `bl`/encoding to disambiguate against the byte-identical
 * iOS validation callback `<ProcessIOSIAP>b__0` at RVA 0x1CF05F0. Those anchors
 * no longer ship — the P2 redirect replaced them — but the lesson stands.) The
 * standard IL2CPP prologue is NOT unique on its own, so the shipped P2 window
 * extends it with the two site-specific `adrp` immediates that follow. Every
 * shipped anchor carries a window measured unique across the whole file, and
 * this engine additionally requires that window to occur **exactly once** at
 * patch time — 0 hits means the offset is wrong, >1 means the window is
 * ambiguous, and both abort the patch with zero bytes written.
 *
 * Replacement length is an INDEPENDENT axis from verify length (12 B written over
 * a 24 B verified window, as with the shipped P2 anchor); [NativeAnchor] asserts
 * only that the replacement *fits inside* the verified window. Where the edited
 * instruction is not at the window start, the replacement repeats the
 * unchanged bytes verbatim so the write still begins at the window start.
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
 * moved/changed app build fails loudly with **zero bytes written**.
 *
 * Only small byte ranges are ever read and written; the uniqueness scan streams the
 * lib through a 1 MiB + overlap buffer instead of slurping 71 MB into the heap.
 *
 * The shipped P2 anchor writes to file offset `0x1CEA874` (RVA `0x1CEE874`) —
 * one edit, so ordering is trivial. If more anchors are added, keep every
 * anchor's *write range* disjoint from every other's window so no anchor can
 * clobber another's result (resolve-then-write protects phase 1 from
 * intermediate states, but phase 2 is plain sequential writes).
 *
 * ============================================================================
 * ABI HANDLING
 * ============================================================================
 * `get("lib/<abi>/libil2cpp.so", true)` resolves inside the RAW_ONLY working dir.
 * ⚠ Head Basketball measured that `listApkEntries("lib/")` returns **EMPTY** when
 * the lib entries are DEFLATE-compressed rather than STOREd (the Crossy Road
 * trap). **Never use the list API here**; resolve the known path directly.
 *
 * An ABI whose lib is simply absent from the APK is skipped with a log line rather
 * than failing the run: `morphe-cli --striplibs arm64-v8a` legitimately removes
 * unpatched ABIs, and there is nothing to patch for an ABI that is not shipped.
 * If *no* targeted ABI is present at all, that is an error and throws.
 *
 * ============================================================================
 * IN-PLACE EDIT ⇒ SAFE ROUND-TRIP
 * ============================================================================
 * Every edit is in place and same-length, so a lib's length never changes. On
 * encode, `detectFileChanges()` re-snapshots the extracted root tree (lastModified
 * + length); our write bumps the mtime, so the file lands in
 * `modifiedBinaryResources`, `getOtherResourceFiles(RAW_ONLY)` returns it, and
 * `ApkUtils.applyTo` overlays it into `rebuilt.apk`, which is then re-signed.
 * DEFLATE re-compression is applied to the edited bytes and the round-trip is
 * byte-lossless for lib content.
 *
 * ============================================================================
 * DO NOT — rules this patch must never violate
 * ============================================================================
 * 1. **Never patch armeabi-v7a from these anchors.** No v7 byte site has been
 *    verified; a wrong guess corrupts an arbitrary function on armv7 devices.
 * 2. **Never touch `global-metadata.dat`.** It is unprotected, but every RVA here
 *    is a code address inside the .so — metadata edits are unnecessary and would
 *    only add risk.
 * 3. **Never widen an edit past its documented instruction(s).** The shipped P2
 *    anchor is a 3-instruction prologue rewrite (`mov x3,x2` ; `mov x2,xzr` ;
 *    `b ProcessFakeIAP`); growing it into a "method stub" would clobber register
 *    state the surviving code depends on.
 * 4. Keep every replacement **same-length and in place** (the engine re-asserts
 *    the file length).
 * 5. Do not add a `--striplibs` that drops the ABI being patched.
 *
 * Byte site: `analysis/com.halfbrick.fruitninjafree/notes/native-freeiap-round2.md`
 * §4.3 P2 redirect (llvm-objdump + capstone-verified; the round-3 verification
 * corrections appended to that file supersede the section's garbled replacement
 * hex — the corrected bytes live in `../il2cpp/ProcessFakeIapRedirect.kt`).
 * Precedent: `app/headbasketball/patches/shared/NativeByteEditor.kt`.
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

package app.deadtarget.patches.ads

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.186.0 — Instant rewarded video
 * (arm64-v8a + armeabi-v7a).
 *
 * One site, one goal: **the reward is granted instantly, with no ad watched.** The "watch an
 * ad for a reward" popup is left completely alone — the game's own logic decides when it
 * appears, so it appears on its normal cadence and then goes away.
 *
 * ============================================================================
 * DUAL-ABI SUPPORT
 * ============================================================================
 * 4.186.0 ships either as an **armeabi-v7a-only** XAPK (APKPure) or, on re-split mirrors
 * such as `apkcube` / current `apkeep` downloads, with **both** `config.arm64_v8a.apk` and
 * `config.armeabi_v7a.apk`. The previous revision hard-coded
 * `get("lib/armeabi-v7a/libil2cpp.so", true)` and read it unconditionally, so an arm64-only
 * merged APK aborted with `FileNotFoundException` before any patching happened. Both
 * libraries are now probed; each present ABI is patched, absent ones are skipped, and only
 * "neither exists" is an error.
 *
 * `AdsController` (MonoBehaviour) owns the whole rewarded flow on both:
 *   * `ShowRewardedVideoAds(int32 segmentId)` — arm32 RVA 0x01C111A4 / arm64 VA 0x0286E5A4
 *   * `OnCompleteShow(bool isNeedSendLog)`     — arm32 RVA 0x01C11704 / arm64 VA 0x0286E9BC
 * `_crtSegmentID` sits at **0x24** in the 32-bit field layout and **0x48** on arm64.
 *
 * ============================================================================
 * WHY ONLY THE TAIL IS REWRITTEN (revision history kept from the 4.185.0 patch)
 * ============================================================================
 * An earlier build also forced five `AdsController` availability predicates to constant
 * `true`. Device testing showed that was wrong: those predicates are the game's own
 * per-segment cooldown/availability logic, so forcing them true made the popup ignore the
 * very state meant to stop it re-prompting ("the popup is appearing again and again"). The
 * tail-branch below was never the problem — the reward *does* arrive — so the gates stay
 * untouched and only the ad request is skipped.
 *
 * ============================================================================
 * THE EDIT — entry stubs
 * ============================================================================
 * ARM32 (12 bytes over the first 12):
 *   24 10 80 E5   str r1, [r0, #0x24]      ← store segmentId into _crtSegmentID
 *   00 10 A0 E3   mov r1, #0               ← isNeedSendLog = false
 *   54 01 00 EA   b  0x01C11704            ← tail-jump into OnCompleteShow
 *
 * ARM64 (12 bytes over the first 12):
 *   01 48 00 B9   str w1, [x0, #0x48]      ← store segmentId into _crtSegmentID
 *   01 00 80 52   mov w1, #0               ← isNeedSendLog = false
 *   04 01 00 14   b  0x0286E9BC            ← tail-jump into OnCompleteShow
 *   (the `b` is at entry+8, hence its displacement 0x410/4 = 0x104)
 *
 * No ad is requested, no mediation call is made, and no availability gate is touched.
 * `OnCompleteShow` then runs the game's full completion path — including
 * `UpdateVideoAdsStatus` → `IncreaseAdsWatchedCounter` / `UpdateAdsWatchedInterval` /
 * `ResetPlayingBattleCounter` — so the cooldown bookkeeping still advances and the popup
 * does not loop.
 *
 * Overwriting the prologue is safe: the stub never touches the stack or a callee-saved
 * register, so the discarded `push`/`sub sp` is simply never executed. `OnCompleteShow`
 * ignores its third `MethodInfo*` argument (non-generic instance method), so the missing
 * `r2`/`x2` is harmless — the same assumption the previous revisions shipped with.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * `PatchEngine` merges the split bundle into one APK first, then a `rawResourcePatch`
 * anywhere in the set forces `ResourceMode.RAW_ONLY`, so `get(path, true)` resolves the
 * raw-extracted library. The write is same-length in place (12-over-12), so
 * `detectFileChanges()` picks it up via `lastModified` and `ApkUtils.applyTo` overlays it
 * into the rebuilt, re-signed APK.
 *
 * ============================================================================
 * ANCHOR
 * ============================================================================
 * A bare prologue is hopelessly ambiguous (thousands of `push {…, lr}` / `sub sp, sp, #…`
 * sites), so each anchor is the smallest window (20 bytes here) verified to occur **exactly
 * once** in its library. A missing or ambiguous anchor aborts the patch before a single
 * byte is written.
 *
 * Deliberately excluded: `MediationManager.IsAdsAvailable` is shared across every
 * `AD_TYPE`, so it cannot be stubbed true without dragging interstitials in too.
 */
@Suppress("unused")
val instantRewardedVideoPatch = rawResourcePatch(
    name = "Instant rewarded video",
    description = "Rewards from \"watch an ad\" popups are granted immediately, without " +
        "playing an ad.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        var patched = 0
        for ((abi, anchors) in listOf(
            "arm64-v8a" to ARM64_ANCHORS,
            "armeabi-v7a" to ARM32_ANCHORS,
        )) {
            val lib = get("lib/$abi/libil2cpp.so", true)
            if (!lib.exists()) {
                println("Dead Target instant rewarded video: lib/$abi/libil2cpp.so absent — skipping ABI")
                continue
            }
            applyAnchors(lib, anchors, abi)
            patched++
        }
        if (patched == 0) {
            throw PatchException(
                "Dead Target instant rewarded video: neither lib/arm64-v8a nor " +
                    "lib/armeabi-v7a libil2cpp.so is present — unsupported app version or " +
                    "split layout?",
            )
        }
        println("Dead Target instant rewarded video: applied to $patched ABI(s)")
    }
}

/**
 * One guarded byte-range replacement in `libil2cpp.so`.
 *
 * [anchorHex] is the ORIGINAL 36-byte window (must occur exactly ONCE in the library — that
 * uniqueness is what makes the match self-verifying); [replacementHex] is the 12-byte stub
 * written over that window's first 12 bytes.
 */
private class Anchor(
    /** Human label used in logs and [PatchException] messages. */
    val label: String,
    /** Virtual address from Il2CppDumper's script.json (= file offset in this ELF). */
    val va: Long,
    /** Exact file offset — identical to [va] for this 32-bit ELF. */
    val fileOffset: Long,
    /** Hex of the ORIGINAL 36-byte window — must occur exactly once, or the patch aborts. */
    val anchorHex: String,
    /** Hex of the 12-byte replacement written at that window's first 12 bytes. */
    val replacementHex: String,
)

/**
 * `str r1,[r0,#0x24] ; mov r1,#0 ; b 0x01C11704` — set `_crtSegmentID` from the argument,
 * then jump straight into the completion handler.
 */
private const val TAIL_REWARD = "241080E50010A0E3540100EA"

/**
 * `str w1,[x0,#0x48] ; mov w1,#0 ; b 0x0286E9BC` — arm64 equivalent of [TAIL_REWARD].
 * The `b` sits at entry+8, displacement (0x0286E9BC − 0x0286E5AC) / 4 = 0x104.
 */
private const val TAIL_REWARD_64 = "014800B90100805204010014"

private val ARM32_ANCHORS = listOf(
    Anchor(
        label = "AdsController.ShowRewardedVideoAds -> set _crtSegmentID, branch to OnCompleteShow",
        va = 0x01C111A4,
        fileOffset = 0x01C111A4,
        anchorHex =
            "F0412DE910D04DE270619FE50050A0E1" +
                "0140A0E106608FE00000D6E5000050E3" +
                "0700001A",
        replacementHex = TAIL_REWARD,
    ),
)

/**
 * The same site on arm64-v8a. VA 0x0286E5A4, file offset 0x0286A5A4 (VA − 0x4000); the
 * 20-byte window occurs exactly once in the 85,202,576-byte library.
 */
private val ARM64_ANCHORS = listOf(
    Anchor(
        label = "AdsController.ShowRewardedVideoAds -> set _crtSegmentID, branch to OnCompleteShow",
        va = 0x0286E5A4,
        fileOffset = 0x0286A5A4,
        anchorHex = "FF4301D1FE1300F9F65703A9F44F04A9154701F0",
        replacementHex = TAIL_REWARD_64,
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
 */
private fun applyAnchors(lib: File, anchors: List<Anchor>, abi: String) {
    println(
        "Dead Target instant rewarded video: patching lib/$abi/libil2cpp.so " +
            "(${lib.length()} bytes)",
    )
    val bytes = lib.readBytes()

    // Phase 1 — resolve every anchor, or abort before touching anything.
    val writes = anchors.map { anchor ->
        val needle = hex(anchor.anchorHex)
        val replacement = hex(anchor.replacementHex)
        val hits = indexOfAll(bytes, needle)
        when {
            hits.isEmpty() -> throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — ${needle.size}-byte " +
                    "anchor not found in ${lib.name} (size=${bytes.size}). Expected file " +
                    "offset 0x${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}). " +
                    "Unsupported app version or ABI?",
            )

            hits.size > 1 -> throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — ${needle.size}-byte " +
                    "anchor is AMBIGUOUS (${hits.size} occurrences: " +
                    hits.joinToString(", ") { "0x" + it.toString(16) } +
                    "). Refusing to guess — unsupported app version?",
            )
        }
        val at = hits[0].toLong()
        if (at + replacement.size > bytes.size) {
            throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — resolved file offset 0x" +
                    "${at.toString(16)} is past end of ${lib.name} (size=${bytes.size}) — " +
                    "app layout changed?",
            )
        }
        val original = needle.copyOf(replacement.size)
        if (!bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size).contentEquals(original)) {
            throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — anchor mismatch at VA 0x" +
                    "${anchor.va.toString(16)} (file 0x${at.toString(16)}): expected " +
                    "${toHex(original)} vs found " +
                    "${toHex(bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size))}. " +
                    "libil2cpp.so layout changed — unsupported app version?",
            )
        }
        if (at != anchor.fileOffset) {
            println(
                "Dead Target instant rewarded video: ${anchor.label} — NOTE: unique anchor " +
                    "resolved to 0x${at.toString(16)}, documented file offset is 0x" +
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
                    "Dead Target instant rewarded video: ${anchor.label} — original bytes " +
                        "changed between resolve and write at file 0x${at.toString(16)}: " +
                        "expected ${toHex(original)} vs found ${toHex(actual)}.",
                )
            }
            raf.seek(at)
            raf.write(replacement)
            println(
                "Dead Target instant rewarded video: VA 0x${anchor.va.toString(16)} " +
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

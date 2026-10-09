package app.swampattack2.patches.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.swampattack2.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK_2
import java.io.File
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Swamp Attack 2 v1.3.9 · **Instant Rewarded Ads (static native patch)**.
 *
 * Unity IL2CPP title: the rewarded flow lives 100 % in `libil2cpp.so`
 * (see notes/ads.md §1/§3, targets N1/N2/N7). With the old runtime engine
 * gone, `VideoAdManager.ShowVideoClip` (arm64 RVA `0x1D4570C`) falls through
 * to a REAL mediation load (`AdMediation.ShowRewardedAd` → Metica →
 * `UnityBridge.showRewarded`) that fails with no fill / blocked SDK, so
 * "watch ad for X" offers show a warning icon and the reward is unobtainable.
 *
 * This patch statically forces the app's OWN "ads disabled" getters to
 * return true — the exact semantics the old engine provided at runtime:
 *
 * * `PlayerData.get_AreVideoAdsDisabled()` (arm64 RVA `0x1CD0D2C`,
 *   arm32 RVA `0xEA8068`) → `ShowVideoClip` takes its in-engine
 *   ads-disabled branch: `RewardedVideoCompleted(completed=true,
 *   skipped=true)` → the stored `Action` fires natively → the reward lands
 *   INSTANTLY with no mediation, no Java, no SDK wait (cannot hang).
 *   `GetAvailabilityStatus` then reports Available (pacing/caps still
 *   honored), so offer buttons stay alive.
 * * `PlayerData.get_AreInterstitialsDisabled()` (arm64 RVA `0x1CD0CFC`,
 *   arm32 RVA `0xEA8030`) → `TryShowInterstitial` early-returns; no forced
 *   interstitial is ever requested (belt-and-braces with the Java
 *   `../ads/AdRemovalPatch`, which stays the display-layer block).
 *
 * Disassembly-verified (arm64 `ShowVideoClip`: `bl 0x1CD0D2C; tbz w0,#0`
 * → disabled path `mov w1,#1; mov w3,#1; bl RewardedVideoCompleted`).
 *
 * Each site's first 8 bytes are overwritten with a return-true stub, so the
 * original body never runs:
 *
 *   arm64  `mov w0, #1 ; ret`  = 20 00 80 52  C0 03 5F D6
 *   arm32  `mov r0, #1 ; bx lr` (ARM state) = 01 00 A0 E3  1E FF 2F E1
 *
 * Overwriting the prologue is safe: the stub returns immediately and never
 * touches the stack, so the discarded frame setup simply never executes —
 * no `sp` imbalance, no link-register leak (`ret` / `bx lr` returns to the
 * untouched caller).
 *
 * arm64 file offset = RVA − 0x4000 (R-E LOAD off 0x1905AB0 = vaddr
 * 0x1909AB0, verified from the ELF program headers of the issue XAPK's own
 * config.arm64_v8a.apk split). arm32 file offset = RVA (single R-E LOAD at
 * off 0 / vaddr 0). Both getters share identical first-16-byte prologues
 * per ABI — that is fine because each site is written at its PINNED offset
 * and gated on its exact expected bytes; a mismatch SKIPS that site
 * (logged, never written blind).
 *
 * Deliberately NOT touched:
 * * `AreAnyAdsDisabled` — reads `AdsData` directly (not via these getters);
 *   the old engine never hooked it and offers stayed alive without it.
 * * Java `UnityBridge.showRewarded` — the native branch completes the
 *   reward before any Java is reached; blocking it would need a real ad
 *   callback object and could strand the C# wait (notes/ads.md §1).
 * * `UnityPlayerActivity.onCreate` — zero trigger edits (static only).
 * * No new `.so` files, no manifest/DEX edits, no trampolines.
 */
@Suppress("unused")
val instantRewardedPatch = rawResourcePatch(
    name = "Swamp Attack 2: Instant Rewarded Ads",
    description = "Watch-ad offers grant their reward instantly with no ad. Forced interstitials stay gone.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK_2)

    execute {
        var applied = 0

        val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
        if (arm64.exists()) {
            applied += patchRewardAbi(arm64, "arm64-v8a", ARM64_REWARD_SITES, expectedElfClass = 2)
        } else {
            println("Swamp Attack 2 instant rewarded: lib/arm64-v8a/libil2cpp.so absent — skipping ABI")
        }

        val arm32 = get("lib/armeabi-v7a/libil2cpp.so", true)
        if (arm32.exists()) {
            applied += patchRewardAbi(arm32, "armeabi-v7a", ARM32_REWARD_SITES, expectedElfClass = 1)
        } else {
            println("Swamp Attack 2 instant rewarded: lib/armeabi-v7a/libil2cpp.so absent — skipping ABI")
        }

        if (applied == 0) {
            throw PatchException(
                "Swamp Attack 2 instant rewarded: no ads-disabled gates applied on any ABI — " +
                    "libil2cpp.so layout changed? Unsupported app version.",
            )
        }
        println("Swamp Attack 2 instant rewarded: $applied ads-disabled gates applied (static, no launch code)")
    }
}

/** One guarded byte-range replacement in `libil2cpp.so`. */
private class RewardGate(
    /** Human label used in logs. */
    val label: String,
    /** RVA from Il2CppDumper's script.json (provenance; kept for messages). */
    val rva: Long,
    /** Exact file offset written (RVA − LOAD delta). */
    val fileOffset: Long,
    /** The expected code words at [fileOffset] (table order = file order). */
    val expectWords: Array<String>,
)

/** arm64 return-true stub: `mov w0, #1 ; ret` (8 bytes, file order). */
private const val REWARD_STUB_ARM64 = "20008052C0035FD6"

/** arm32 return-true stub: `mov r0, #1 ; bx lr` (8 bytes, file order, ARM state). */
private const val REWARD_STUB_ARM32 = "0100A0E31EFF2FE1"

private val ARM64_REWARD_SITES = listOf(
    RewardGate(
        label = "player.AreInterstitialsDisabled -> true (no forced interstitials)",
        rva = 0x1CD0CFC,
        fileOffset = 0x1CCCCFC,
        expectWords = arrayOf("F81F0FFE", "F9400C08", "B4000128", "F9406100"),
    ),
    RewardGate(
        label = "player.AreVideoAdsDisabled -> true (instant rewarded)",
        rva = 0x1CD0D2C,
        fileOffset = 0x1CCCD2C,
        expectWords = arrayOf("F81F0FFE", "F9400C08", "B4000128", "F9406100"),
    ),
)

private val ARM32_REWARD_SITES = listOf(
    RewardGate(
        label = "player.AreInterstitialsDisabled -> true (no forced interstitials)",
        rva = 0xEA8030,
        fileOffset = 0xEA8030,
        expectWords = arrayOf("E92D4010", "E590400C", "E3540000", "1A000000"),
    ),
    RewardGate(
        label = "player.AreVideoAdsDisabled -> true (instant rewarded)",
        rva = 0xEA8068,
        fileOffset = 0xEA8068,
        expectWords = arrayOf("E92D4010", "E590400C", "E3540000", "1A000000"),
    ),
)

/**
 * Applies [sites] to [lib], gated per site on exact expected bytes.
 *
 * In-place writes (8-byte return-true stubs in 16-byte windows), so the
 * patcher's `lastModified`-keyed change diff picks them up. A site whose
 * bytes don't match is SKIPPED with a log line — never written blind.
 * Returns the number of sites applied.
 */
private fun patchRewardAbi(lib: File, abi: String, sites: List<RewardGate>, expectedElfClass: Int): Int {
    val bytes = lib.readBytes()
    println("Swamp Attack 2 instant rewarded: patching $abi libil2cpp.so (${bytes.size} bytes)")

    if (bytes.size < 64 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
    ) {
        throw PatchException(
            "Swamp Attack 2 instant rewarded: lib/$abi/libil2cpp.so is not an ELF file — " +
                "unexpected split layout?",
        )
    }
    // EI_CLASS (offset 4): 1 = 32-bit (ARM), 2 = 64-bit (AArch64).
    val eiClass = bytes[4].toInt() and 0xFF
    if (eiClass != expectedElfClass) {
        throw PatchException(
            "Swamp Attack 2 instant rewarded: lib/$abi/libil2cpp.so has ELF class $eiClass, " +
                "expected $expectedElfClass — ABI/lib mismatch, refusing to write.",
        )
    }

    val stub = rewardHex(if (expectedElfClass == 2) REWARD_STUB_ARM64 else REWARD_STUB_ARM32)
    var applied = 0
    for (site in sites) {
        val expect = rewardWords(*site.expectWords)
        val off = site.fileOffset
        if (off < 0 || off + expect.size > bytes.size) {
            println(
                "Swamp Attack 2 instant rewarded: SKIP ${site.label} [$abi] — " +
                    "file offset 0x${off.toString(16)} out of range (size=${bytes.size}).",
            )
            continue
        }
        val at = off.toInt()
        val actual = bytes.copyOfRange(at, at + expect.size)
        if (!actual.contentEquals(expect)) {
            println(
                "Swamp Attack 2 instant rewarded: SKIP ${site.label} [$abi] — " +
                    "expected ${rewardToHex(expect)} at 0x${off.toString(16)} but found " +
                    "${rewardToHex(actual.copyOf(8))}… (RVA 0x${site.rva.toString(16)}).",
            )
            continue
        }
        stub.copyInto(bytes, at)
        applied++
        println(
            "Swamp Attack 2 instant rewarded: [$abi] ${site.label} " +
                "at file 0x${off.toString(16)} (RVA 0x${site.rva.toString(16)}) -> patched.",
        )
    }
    if (applied > 0) {
        lib.writeBytes(bytes)
    }
    println("Swamp Attack 2 instant rewarded: $abi — $applied/${sites.size} gates applied")
    return applied
}

/** Packs code words (as printed, e.g. "F9400C08") into little-endian file bytes. */
private fun rewardWords(vararg w: String): ByteArray {
    val out = ByteArray(w.size * 4)
    w.forEachIndexed { i, s ->
        val v = s.toLong(16).toInt()
        out[i * 4] = (v and 0xFF).toByte()
        out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
        out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
        out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
    }
    return out
}

/** Parses a plain hex string (no separators, file byte order) into bytes. */
private fun rewardHex(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX …" formatter for mismatch messages. */
private fun rewardToHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

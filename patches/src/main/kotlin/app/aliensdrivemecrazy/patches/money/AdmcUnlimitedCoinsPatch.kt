package app.aliensdrivemecrazy.patches.money

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Aliens Drive Me Crazy — Unlimited Coins (native il2cpp, 3.2.10-only)
 *
 * The game is Unity IL2CPP: all coin logic lives in libil2cpp.so, the DEX layer
 * is only SDK glue.
 *
 *   lib/arm64-v8a/libil2cpp.so @ file offset 0x016FDB88 (VA 0x1701B88)
 *     inside Org.Awake — the call whose result is stored to [x19, #0x28]
 *     = Org.TotalCoins (field offset 0x28):
 *
 *       bc be 04 94   bl  Load.LoadInt      (VA 0x1831678, PlayerPrefs int loader)
 *         →
 *       00 00 b6 12   mov w0, #0x4FFFFFFF   (= 1,342,177,279 coins)
 *
 * Every coin save-read therefore returns ~1.34 billion.
 *
 * Matching is anchor-based (shadowfight/vector patch pattern): the 12-byte
 * sequence [bl Load.LoadInt][ldr x8,[x27]][str w0,[x19,#0x28]] occurs exactly
 * once in the whole 3.2.10 56 MB library (versionCode 50), which makes the
 * match self-verifying — if Neko (Rebel Twins) ships a new build, the anchor
 * moves and the patch fails loudly instead of corrupting anything.
 * 3.2.10 anchor (1 hit, byte-verified):
 *   BCBE0494 680340F9 602A00B9 @ file 0x16FDB88
 *     (Org.Awake VA 0x17019D0 + 0x1B8;
 *     bl word 9404BEBC -> Load.LoadInt VA 0x1831678, verified).
 * Tail: ldr x8,[x27]; str w0,[x19,#0x28] with Org.TotalCoins still
 * field 0x28 in dump.cs.
 * ⚠️ Il2CppDumper reports VIRTUAL addresses; the executable LOAD segment maps
 * file 0x137FF00 → VA 0x1383F00 (delta +0x4000). Always recompute file offsets
 * from script.json VAs via the program headers when retargeting versions.
 */
@Suppress("unused")
val admcUnlimitedCoinsPatch = rawResourcePatch(
    name = "Unlimited coins",
    description = "Coin save reads always return ~1.34 billion coins (native libil2cpp.so patch on Org.TotalCoins).",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADMC)

    execute {
        val soFile = get("lib/arm64-v8a/libil2cpp.so", true)
        val bytes = soFile.readBytes()

        // 3.2.10-only anchor: bl #Load.LoadInt ; ldr x8,[x27] ;
        // str w0,[x19,#0x28] (Org.TotalCoins store) @ file 0x16FDB88.
        // Replacement: mov w0, #0x4FFFFFFF.
        val pattern = hex("BCBE0494 680340F9 602A00B9")
        // Replacement for the first word: mov w0, #0x4FFFFFFF  (1,342,177,279)
        val replacement = hex("0000B612")

        println("ADMC Unlimited Coins: libil2cpp.so size=" + bytes.size + " bytes")
        val idx = indexOfPattern(bytes, pattern)
        if (idx < 0) {
            throw PatchException(
                "ADMC Unlimited Coins: Org.TotalCoins anchor not found in libil2cpp.so (size=" +
                        bytes.size + " bytes) — unsupported game version?"
            )
        }

        replacement.copyInto(bytes, idx, 0, replacement.size)
        soFile.writeBytes(bytes)
        println("ADMC Unlimited Coins (3.2.10): bl Load.LoadInt -> mov w0,#0x4FFFFFFF at file offset 0x" + idx.toString(16))
    }
}

/** Parses a big-endian hex string (spaces optional) into a byte array. */
private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun indexOfPattern(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    val last = haystack.size - needle.size
    var i = 0
    while (i <= last) {
        var match = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return i
        i++
    }
    return -1
}

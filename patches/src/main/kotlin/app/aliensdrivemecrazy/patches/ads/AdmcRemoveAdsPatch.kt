package app.aliensdrivemecrazy.patches.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Aliens Drive Me Crazy — Remove ads (native il2cpp, 3.2.10-only)
 *
 * Kills every interstitial ("commercial break") path while leaving the
 * reward dispatcher untouched for the Instant rewards patch:
 *
 *   1. MyIronSource.ShowInterstitial  (VA 0x1846514) -> RET
 *   2. MyIronSource.LoadIntersitial   (VA 0x1846374) -> RET  (typo is the game's)
 *   3. MyIronSource.CzyMozeBycInterstitial (VA 0x184620C, returns bool!)
 *        -> mov w0, #0 ; RET  — bare RET would return `this` != 0 = true (ads on!)
 *   4. Legacy Unity-Ads path MyAds.PokazInterstital (VA 0x183F830) -> RET
 *
 * All four sites are matched by single 3.2.10-only instruction anchors
 * (each verified unique — 1 hit — in the 3.2.10 56 MB library,
 * versionCode 50), so a future game build fails loudly instead of
 * patching the wrong function.
 */
@Suppress("unused")
val admcRemoveAdsPatch = rawResourcePatch(
    name = "Remove ads",
    description = "Stops all interstitial/commercial-break ads between games (native libil2cpp.so patch, rewarded videos handled by the instant-rewards patch).",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADMC)

    execute {
        val soFile = get("lib/arm64-v8a/libil2cpp.so", true)
        val bytes = soFile.readBytes()

        val ret = hex("C0035FD6")   // RET
        val nop = hex("1F2003D5")   // NOP

        // 3.2.10-only anchors (byte-verified prologues, 1 hit each):
        //   ShowInterstitial  VA 0x1846514 (6 words):
        //     FE5FBDA9 F65701A9 F44F02A9 D6EA00D0 77D90090 75D90090
        //   LoadIntersitial   VA 0x1846374 (6 words, minimum):
        //     FE0F1CF8 F85F01A9 F65702A9 F44F03A9 D3EA00D0 14D900D0
        //   CzyMozeByc...     VA 0x184620C (6 words):
        //     FE0F1DF8 F65701A9 F44F02A9 D6EA00D0 75D90090 F4D800F0
        //   PokazInterstital  VA 0x183F830 (4 words):
        //     FE0F1DF8 F65701A9 F44F02A9 16EB00B0
        val sites = listOf(
            "MyIronSource.ShowInterstitial" to (
                "FE5FBDA9F65701A9F44F02A9D6EA00D077D9009075D90090" to 6
            ),
            "MyIronSource.LoadIntersitial" to (
                "FE0F1CF8F85F01A9F65702A9F44F03A9D3EA00D014D900D0" to 6
            ),
            "MyIronSource.CzyMozeBycInterstitial (bool)" to (
                "FE0F1DF8F65701A9F44F02A9D6EA00D075D90090F4D800F0" to 6
            ),
            "MyAds.PokazInterstital (legacy)" to (
                "FE0F1DF8F65701A9F44F02A916EB00B0" to 4
            ),
        )

        for ((name, pair) in sites) {
            val (anchorHex, words) = pair
            val pattern = hex(anchorHex)
            val idx = indexOfPattern(bytes, pattern)
            if (idx < 0) {
                throw PatchException("ADMC Remove ads: anchor for $name not found - unsupported game version?")
            }
            // mov w0, #0 ; RET ; NOP ...  — w0=0 is correct for void AND bool methods
            val pad = ByteArray((words - 2) * 4) { i -> nop[i % 4] }
            val replacement = hex("00008052") + ret + pad
            replacement.copyInto(bytes, idx, 0, replacement.size)
            println("ADMC Remove ads: stubbed $name at file offset 0x" + idx.toString(16))
        }

        soFile.writeBytes(bytes)
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

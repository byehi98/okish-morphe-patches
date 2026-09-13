package app.aliensdrivemecrazy.patches.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Aliens Drive Me Crazy — Remove ads (native il2cpp)
 *
 * Kills every interstitial ("commercial break") path while leaving the
 * reward dispatcher untouched for the Instant rewards patch:
 *
 *   1. MyIronSource.ShowInterstitial  (VA 0x183E744) -> RET
 *   2. MyIronSource.LoadIntersitial   (VA 0x183E5AC) -> RET  (typo is the game's)
 *   3. MyIronSource.CzyMozeBycInterstitial (VA 0x183E444, returns bool!)
 *        -> mov w0, #0 ; RET  — bare RET would return `this` != 0 = true (ads on!)
 *   4. Legacy Unity-Ads path MyAds.PokazInterstital (VA 0x1838128) -> RET
 *
 * All four sites are matched by unique 16-24 byte instruction anchors
 * (verified unique across the whole 56 MB library for 3.2.7 / versionCode 46),
 * so a future game build fails loudly instead of patching the wrong function.
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

        // anchor hex (unique), number of 4-byte words it covers
        val sites = listOf(
            "MyIronSource.ShowInterstitial" to
                ("FE0F1DF8F65701A9F44F02A9B4EA00F0F5D800F0" to 5),
            "MyIronSource.LoadIntersitial" to
                ("FE0F1CF8F85F01A9F65702A9F44F03A9B3EA00F0" to 5),
            "MyIronSource.CzyMozeBycInterstitial (bool)" to
                ("FE0F1DF8F65701A9F44F02A9B6EA00F055D900B0F4D800B0" to 6),
            "MyAds.PokazInterstital (legacy)" to
                ("FE0F1DF8F65701A9F44F02A9F6EA00B0" to 4),
        )

        for ((name, anchorPair) in sites) {
            val (anchorHex, words) = anchorPair
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

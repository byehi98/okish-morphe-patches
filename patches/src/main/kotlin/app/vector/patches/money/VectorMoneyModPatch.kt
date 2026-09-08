package app.vector.patches.money

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.vector.patches.shared.Constants.COMPATIBILITY_VECTOR
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Vector — Money Mod (native il2cpp)
 *
 *   lib/arm64-v8a/libil2cpp.so @ 0x5F8ADE8
 *     SUB X8, X8, X19   (0xCB130108)  →  ADD X8, X8, X19  (0x8B130108)
 *
 * This is the game's spend routine: `balance -= cost` becomes
 * `balance += cost` — every purchase ADDS the price back, so spending money
 * increases your balance (the classic "money grows when you spend" mod).
 *
 */
@Suppress("unused")
val vectorMoneyModPatch = rawResourcePatch(
    name = "Vector Money Mod",
    description = "Spending money increases your balance instead of decreasing it (native libil2cpp.so patch).",
    default = true
) {
    compatibleWith(COMPATIBILITY_VECTOR)

    execute {
        val soFile = get("lib/arm64-v8a/libil2cpp.so", true)
        val bytes = soFile.readBytes()

        // 16-byte anchor: ldr x8,[x8,#0x20]; mov x20,xzr; movz w22,#5; SUB x8,x8,x19
        val pattern = hex("081040F9 F4031FAA B6008052 080113CB")
        val replacement = hex("081040F9 F4031FAA B6008052 0801138B") // ADD x8, x8, x19

        println("Vector Money Mod: libil2cpp.so size=" + bytes.size + " bytes")
        val idx = indexOfPattern(bytes, pattern)
        if (idx < 0) {
            throw PatchException(
                "Vector Money Mod: spend-routine anchor not found in libil2cpp.so (size=" + bytes.size + " bytes) — app layout changed?"
            )
        }

        replacement.copyInto(bytes, idx, 0, replacement.size)
        soFile.writeBytes(bytes)
        println("Vector Money Mod: flipped SUB->ADD at offset 0x" + idx.toString(16))
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

package app.crossyroad.patches.billing

import app.crossyroad.patches.shared.Constants.COMPATIBILITY_CROSSY_ROAD
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Crossy Road — Free store, native character branch (7.13.0, ARM32).
 *
 * Complements the DEX "Free store" forge: makes PAID CHARACTERS take the
 * game's own local-grant path without ever entering Google Play billing.
 *
 * lib/armeabi-v7a/libil2cpp.so — file offset == RVA for the executable LOAD
 * segment (readelf: Offset 0x0 = VirtAddr 0x0; verified delta 0). Target
 * inside UniversalStoreManager.PurchaseRequest (RVA 0x13469AC):
 *
 *   0x1346AB0   bl      Character.GetConfiguredPurchaseType   ; FreeInternal==1
 *   0x1346AB4   cmp     r0, #1
 *   0x1346AB8   bne     0x1346B30      ← NOP this ( PaidIAP → billing path )
 *   0x1346ABC   mov     r0, r6         ← fall-through: direct local-grant path
 *                                       ( SetBonusCoins → HandleProductPurchase
 *                                         0x1346E0C → PurchaseProductBundle → grant )
 *
 * The earlier `character == null` beq (non-character products → billing) is
 * untouched, so coin packs / bundles still flow through the DEX forge — this
 * patch only reroutes products that resolved to a Character with a paid
 * purchase type. The free-grant path dereferences the character (r6), but the
 * null-character check upstream still diverts lookups away — no NPE.
 *
 * Matching is anchor-based (shadowfight/vector/ADMC pattern): the 12-byte
 * sequence [cmp r0,#1][bne][mov r0,r6] occurs EXACTLY ONCE in the whole
 * 73,234,644-byte 7.13.0 library (byte-verified), so the match is
 * self-verifying — a new build moves the anchor and the patch fails loudly
 * instead of corrupting anything. The 8-byte tail alone occurs 21× (hence the
 * leading cmp word).
 *
 * 7.13.0 anchor (1 hit, byte-verified): 010050E3 1C00001A 0600A0E1 @ 0x1346AB4
 * Replacement @ 0x1346AB8: 0000A0E1  (= 0xE1A00000, `mov r0, r0` NOP).
 */
@Suppress("unused")
val crossyRoadFreeStoreNativePatch = rawResourcePatch(
    name = "Free store (character branch)",
    description = "The in-game store is free. Just tap \"Buy\" and the item is yours — no payment needed. For an ad-free game, just buy the ad-block item from the store.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CROSSY_ROAD)

    execute {
        val soFile = get("lib/armeabi-v7a/libil2cpp.so", true)
        val bytes = soFile.readBytes()

        // cmp r0,#1 ; bne→billing ; mov r0,r6  (PurchaseRequest @ 0x1346AB4)
        val pattern = hex("010050E3 1C00001A 0600A0E1")
        // mov r0,r0 (NOP) — replaces ONLY the bne word at pattern offset +4.
        val replacement = hex("0000A0E1")

        println("Crossy Road Free store (native): libil2cpp.so size=" + bytes.size + " bytes")
        val idx = indexOfPattern(bytes, pattern)
        if (idx < 0) {
            throw PatchException(
                "Crossy Road Free store (native): PurchaseRequest branch anchor not found in " +
                        "libil2cpp.so (size=" + bytes.size + " bytes) — unsupported game version?"
            )
        }

        replacement.copyInto(bytes, idx + 4)
        soFile.writeBytes(bytes)
        println(
            "Crossy Road Free store (native): bne→billing NOP'd at file offset 0x" +
                    (idx + 4).toString(16) +
                    (if (idx != 0x1346AB4) " (expected 0x1346ab4 — anchor moved, verify!)" else "")
        )
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

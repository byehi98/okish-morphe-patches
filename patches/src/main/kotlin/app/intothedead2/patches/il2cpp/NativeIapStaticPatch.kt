package app.intothedead2.patches.il2cpp

import app.intothedead2.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD_2
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Static (on-disk) native IAP patch for Into the Dead 2 1.88.0.
 *
 * Replaces the old approach of shipping a helper .so that patched libil2cpp.so in memory at
 * runtime: the 12 decision points are rewritten directly in lib/arm64-v8a/libil2cpp.so while
 * the APK is being built. Nothing is loaded at runtime and there is no window after launch
 * where purchases are not yet intercepted.
 *
 * Targets (arm64, vaddr; .text file offset = vaddr - 0x4000):
 *   verdict                  0x1D70B40  0x36000768 -> nop
 *   rcptst-dr2               0x1D70B60  0xB9001A88 -> str wzr, [x20, #0x18]
 *   rcptst-base              0x2017FDC  0xB9001A88 -> str wzr, [x20, #0x18]
 *   complete-gate            0x2018828  0xB9401909 -> mov w9, #0
 *   subs-verdict             0x201AF40  0x36000520 -> nop
 *   vip-gate-0/1             0x1DBD418  0xF81E0FFE / 0xA9014FF4 -> mov w0, #1 ; ret
 *   no-expiry                0x1DBAA4C  0xA9BE57FE -> ret
 *   subs-rewards-0/1         0x1DBB6CC  0xD10103FF / 0xA9015FFE -> mov w0, #1 ; ret
 *   busy-0/1                 0x20191C0  0xD10143FF / 0xA90167FE -> mov w0, #0 ; ret
 *
 * Anchors were generated against the 1.88.0 XAPK's libil2cpp.so
 * (72,358,232 B, sha256 877bca6f4583f6be9490a057326ab366d02c636074272f0d6a890d38bb1572bc):
 * every 12-24 byte pattern occurs exactly once in the file, spans do not overlap, and the
 * replacement set was verified order-independent over 20 random application orders.
 *
 * Updating for a new game version: re-derive the RVAs with Il2CppDumper, confirm the current
 * word at each site, then regenerate these anchors (see
 * analysis/com.pikpok.dr2.play/notes/native-engine-so-embedding.md). hexPatch throws a
 * PatchException when a pattern is missing, so a stale anchor fails the build instead of
 * producing an APK whose native patches silently do nothing.
 */
private const val LIB = "lib/arm64-v8a/libil2cpp.so"

private val iapSites: HexPatchBuilder.() -> Unit = {
        // verdict @ file 0x1d6cb38
        "E8 03 00 2A 60 1A 40 F9 68 07 00 36 60 10 00 B4 54 10 00 B4" asPatternTo
        "E8 03 00 2A 60 1A 40 F9 1F 20 03 D5 60 10 00 B4 54 10 00 B4" inFile LIB

        // rcptst-dr2 @ file 0x1d6cb58
        "E8 17 9F 1A 08 79 1F 53 88 1A 00 B9 69 0F 00 B4 AA 25 01 D0" asPatternTo
        "E8 17 9F 1A 08 79 1F 53 9F 1A 00 B9 69 0F 00 B4 AA 25 01 D0" inFile LIB

        // rcptst-base @ file 0x2013fd4
        "E8 17 9F 1A 08 79 1F 53 88 1A 00 B9 69 12 00 B4 6A 10 01 F0" asPatternTo
        "E8 17 9F 1A 08 79 1F 53 9F 1A 00 B9 69 12 00 B4 6A 10 01 F0" inFile LIB

        // complete-gate @ file 0x2014820
        "08 11 40 F9 28 32 00 B4 09 19 40 B9 68 12 40 F9 09 1E 00 34" asPatternTo
        "08 11 40 F9 28 32 00 B4 09 00 80 52 68 12 40 F9 09 1E 00 34" inFile LIB

        // subs-verdict @ file 0x2016f38
        "E1 03 1F AA 53 A1 01 94 20 05 00 36 E8 10 01 B0 E1 03 13 AA" asPatternTo
        "E1 03 1F AA 53 A1 01 94 1F 20 03 D5 E8 10 01 B0 E1 03 13 AA" inFile LIB

        // vip-gate-0 @ file 0x1db9410
        "25 C5 7C 14 1B D8 F6 97 FE 0F 1E F8" asPatternTo
        "25 C5 7C 14 1B D8 F6 97 20 00 80 52" inFile LIB

        // vip-gate-1 @ file 0x1db941c
        "F4 4F 01 A9 94 3A 01 90 F3 03 00 AA 88 D6 6F 39" asPatternTo
        "C0 03 5F D6 94 3A 01 90 F3 03 00 AA 88 D6 6F 39" inFile LIB

        // no-expiry @ file 0x1db6a44
        "91 E2 F6 97 8E E2 F6 97 FE 57 BE A9 F4 4F 01 A9 95 3A 01 F0" asPatternTo
        "91 E2 F6 97 8E E2 F6 97 C0 03 5F D6 F4 4F 01 A9 95 3A 01 F0" inFile LIB

        // subs-rewards-0 @ file 0x1db76b8
        "00 01 40 F9 F4 4F 42 A9 FE 57 41 A9 FF C3 00 91 C0 03 5F D6 FF 03 01 D1" asPatternTo
        "00 01 40 F9 F4 4F 42 A9 FE 57 41 A9 FF C3 00 91 C0 03 5F D6 20 00 80 52" inFile LIB

        // subs-rewards-1 @ file 0x1db76d0
        "FE 5F 01 A9 F6 57 02 A9 F4 4F 03 A9 94 3A 01 D0" asPatternTo
        "C0 03 5F D6 F6 57 02 A9 F4 4F 03 A9 94 3A 01 D0" inFile LIB

        // busy-0 @ file 0x20151b8
        "C0 03 5F D6 B1 68 ED 97 FF 43 01 D1" asPatternTo
        "C0 03 5F D6 B1 68 ED 97 00 00 80 52" inFile LIB

        // busy-1 @ file 0x20151c4
        "FE 67 01 A9 F8 5F 02 A9 F6 57 03 A9 F4 4F 04 A9 B4 27 01 D0" asPatternTo
        "C0 03 5F D6 F8 5F 02 A9 F6 57 03 A9 F4 4F 04 A9 B4 27 01 D0" inFile LIB
}

@Suppress("unused")
val nativeIapStaticPatch = rawResourcePatch(
    name = "Native IAP Patch",
    description = "Patches the game's native code directly so purchases and subscriptions " +
        "(including VIP) are approved on your own device, with no helper library loaded at " +
        "runtime.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD_2)

    dependsOn(hexPatch(block = iapSites))
}

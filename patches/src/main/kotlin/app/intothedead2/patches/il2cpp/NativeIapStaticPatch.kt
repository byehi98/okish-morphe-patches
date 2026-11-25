package app.intothedead2.patches.il2cpp

import app.intothedead2.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD_2
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Static (on-disk) native IAP patch for Into the Dead 2 1.89.0, arm64-v8a **and** armeabi-v7a.
 *
 * Replaces the old approach of shipping a helper .so that patched libil2cpp.so in memory at
 * runtime: the decision points are rewritten directly in both libil2cpp.so files while the
 * APK is being built. Nothing is loaded at runtime and there is no window after launch where
 * purchases are not yet intercepted.
 *
 * Function-level targets (IL2CPP metadata names, identical on both ABIs):
 *   DR2PurchaseFlowHandler.<VerifyReceipt>d__*.MoveNext   verdict + receipt status (dr2)
 *   APurchaseFlowHandler.<VerifyReceipt>d__10.MoveNext    receipt status (base flow)
 *   DefaultPikPokFlowHandler.<PurchaseCompleteFlow>d__2   completed-purchase gate
 *   PurchaseManager.OnSubscriptionDataRecieved            subscription verdict branch
 *   VIPManager.get_VIPActive                              -> true
 *   SubscriptionManager.CheckExpiryDate                   -> return (no expiry)
 *   SubscriptionManager.ProcessSubscriptionRewards        -> 1
 *   PurchaseManager.get_Busy                              -> false
 *
 * 1.89.0 anchors were derived as follows: the 1.88.0 XAPK (arm64 lib sha256
 * 877bca6f4583f6be9490a057326ab366d02c636074272f0d6a890d38bb1572bc, armv7 lib
 * a4daf830ea3122b91166a4c66adda954875b79689ef3e8a6bdcbdb02160b0355) was dumped with
 * Il2CppDumper, every 1.88.0 patch site was mapped to its containing method, and the same
 * methods were re-located in the 1.89.0 dump:
 *   arm64 lib sha256 aa3e3403da3fb6942353a5b4c35357a9d5802a9208c5ca164c0f4175d798c6dc
 *   armv7 lib sha256 2969b4405ee3218d9179b6786fc454d381d4d00dfb7b7afb57eed0a1f7bda77b
 *
 * Every anchor below was verified to occur exactly once in its target file. hexPatch throws a
 * PatchException when a pattern is missing, so a stale anchor fails the build instead of
 * producing an APK whose native patches silently do nothing.
 *
 * Updating for a new game version: re-dump both libil2cpp.so files with Il2CppDumper, look up
 * the methods above, confirm the current words at each site, then regenerate the anchors.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libil2cpp.so"
private const val LIB_ARMV7 = "lib/armeabi-v7a/libil2cpp.so"

/**
 * arm64-v8a sites. Each pattern/replacement pair documents the patched instruction:
 *   verdict        tbz  w8, #0, ...      -> nop
 *   rcptst-*       str  w8, [xZ, #0x18]  -> str wzr, [xZ, #0x18]
 *   complete-gate  ldr  w8, [x8, #0x18]  -> mov w8, #0
 *   subs-verdict   tbz  w0, #0, ...      -> nop
 *   vip-gate       prologue              -> mov w0, #1 ; ret
 *   no-expiry      stp  x30, x21, ...    -> ret
 *   subs-rewards   sub sp / stp prologue -> mov w0, #1 ; ret
 *   busy           sub sp / stp prologue -> mov w0, #0 ; ret
 */
private val iapSitesArm64: HexPatchBuilder.() -> Unit = {
    // DR2PurchaseFlowHandler.<VerifyReceipt>.MoveNext — verdict branch (tbz -> nop).
    "E8 03 00 2A 60 1A 40 F9 48 04 00 36 40 19 00 B4 34 19 00 B4" asPatternTo
        "E8 03 00 2A 60 1A 40 F9 1F 20 03 D5 40 19 00 B4 34 19 00 B4" inFile LIB_ARM64

    // DR2PurchaseFlowHandler.<VerifyReceipt>.MoveNext — receipt status store (w8 -> wzr).
    "E8 17 9F 1A 08 79 1F 53 88 1A 00 B9 49 18 00 B4 2A 26 01 B0" asPatternTo
        "E8 17 9F 1A 08 79 1F 53 9F 1A 00 B9 49 18 00 B4 2A 26 01 B0" inFile LIB_ARM64

    // APurchaseFlowHandler.<VerifyReceipt>.MoveNext — receipt status store (w8 -> wzr).
    "E8 17 9F 1A 08 79 1F 53 88 1A 00 B9 69 12 00 B4 8A 10 01 F0" asPatternTo
        "E8 17 9F 1A 08 79 1F 53 9F 1A 00 B9 69 12 00 B4 8A 10 01 F0" inFile LIB_ARM64

    // DefaultPikPokFlowHandler.<PurchaseCompleteFlow>.MoveNext — completion gate (w8 = 0).
    "08 11 40 F9 28 44 00 B4 08 19 40 B9 08 21 00 34 69 12 40 F9" asPatternTo
        "08 11 40 F9 28 44 00 B4 08 00 80 52 08 21 00 34 69 12 40 F9" inFile LIB_ARM64

    // PurchaseManager.OnSubscriptionDataRecieved — subscription verdict branch (tbz -> nop).
    "E1 03 1F AA 6C A4 01 94 20 05 00 36 08 11 01 B0 E1 03 13 AA" asPatternTo
        "E1 03 1F AA 6C A4 01 94 1F 20 03 D5 08 11 01 B0 E1 03 13 AA" inFile LIB_ARM64

    // VIPManager.get_VIPActive — always true (prologue -> mov w0, #1 ; ret).
    "FE 0F 1E F8 F4 4F 01 A9 F4 3A 01 F0 F3 03 00 AA 88 12 7C 39" asPatternTo
        "20 00 80 52 C0 03 5F D6 F4 3A 01 F0 F3 03 00 AA 88 12 7C 39" inFile LIB_ARM64

    // SubscriptionManager.CheckExpiryDate — subscriptions never expire (prologue -> ret).
    "FE 57 BE A9 F4 4F 01 A9 15 3B 01 D0 F4 03 01 AA F3 03 00 AA A8 A2 7B 39" asPatternTo
        "C0 03 5F D6 F4 4F 01 A9 15 3B 01 D0 F4 03 01 AA F3 03 00 AA A8 A2 7B 39" inFile LIB_ARM64

    // SubscriptionManager.ProcessSubscriptionRewards — report one reward (prologue -> mov w0, #1 ; ret).
    "FF 03 01 D1 FE 5F 01 A9 F6 57 02 A9 F4 4F 03 A9 14 3B 01 B0 F3 03 01 AA" asPatternTo
        "20 00 80 52 C0 03 5F D6 F6 57 02 A9 F4 4F 03 A9 14 3B 01 B0 F3 03 01 AA" inFile LIB_ARM64

    // PurchaseManager.get_Busy — never busy (prologue -> mov w0, #0 ; ret).
    "FF 43 01 D1 FE 67 01 A9 F8 5F 02 A9 F6 57 03 A9 F4 4F 04 A9 F4 27 01 90" asPatternTo
        "00 00 80 52 C0 03 5F D6 F8 5F 02 A9 F6 57 03 A9 F4 4F 04 A9 F4 27 01 90" inFile LIB_ARM64
}

/**
 * armeabi-v7a sites (A32). Same decision points, ARM encodings:
 *   verdict        beq                 -> nop   (mov r0, r0)
 *   rcptst-*       lsl r5, r0, #1      -> mov r5, #0
 *   complete-gate  ldr r0, [r5, #0xc]  -> mov r0, #0
 *   subs-verdict   beq                 -> nop
 *   vip-gate       prologue            -> mov r0, #1 ; bx lr
 *   no-expiry      prologue            -> bx lr
 *   subs-rewards   prologue            -> mov r0, #1 ; bx lr
 *   busy           prologue            -> mov r0, #0 ; bx lr
 */
private val iapSitesArmv7: HexPatchBuilder.() -> Unit = {
    // DR2PurchaseFlowHandler.<VerifyReceipt>.MoveNext — verdict branch (beq -> nop).
    "18 60 94 E5 00 00 50 E3 26 00 00 0A 00 00 56 E3 00 00 00 1A" asPatternTo
        "18 60 94 E5 00 00 50 E3 00 00 A0 E1 00 00 56 E3 00 00 00 1A" inFile LIB_ARMV7

    // DR2PurchaseFlowHandler.<VerifyReceipt>.MoveNext — receipt status value (lsl -> mov r5, #0).
    "10 0F 6F E1 A0 02 A0 E1 80 50 A0 E1 00 00 00 1A 49 60 F7 EB" asPatternTo
        "10 0F 6F E1 A0 02 A0 E1 00 50 A0 E3 00 00 00 1A 49 60 F7 EB" inFile LIB_ARMV7

    // APurchaseFlowHandler.<VerifyReceipt>.MoveNext — receipt status value (lsl -> mov r5, #0).
    "10 0F 6F E1 A0 02 A0 E1 80 50 A0 E1 00 00 00 1A 24 95 E9 EB" asPatternTo
        "10 0F 6F E1 A0 02 A0 E1 00 50 A0 E3 00 00 00 1A 24 95 E9 EB" inFile LIB_ARMV7

    // DefaultPikPokFlowHandler.<PurchaseCompleteFlow>.MoveNext — completion gate (ldr -> mov r0, #0).
    "00 00 55 E3 00 00 00 1A 02 92 E9 EB 0C 00 95 E5 00 00 50 E3" asPatternTo
        "00 00 55 E3 00 00 00 1A 02 92 E9 EB 00 00 A0 E3 00 00 50 E3" inFile LIB_ARMV7

    // PurchaseManager.OnSubscriptionDataRecieved — subscription verdict branch (beq -> nop).
    "00 10 A0 E3 8E 0A 02 EB 00 00 50 E3 2D 00 00 0A 18 04 9F E5" asPatternTo
        "00 10 A0 E3 8E 0A 02 EB 00 00 50 E3 00 00 A0 E1 18 04 9F E5" inFile LIB_ARMV7

    // VIPManager.get_VIPActive — always true (prologue -> mov r0, #1 ; bx lr).
    "30 48 2D E9 4C 50 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 04 00 00 1A 38 00 9F E5 00 00 9F E7 98 D1 F5 EB" asPatternTo
        "01 00 A0 E3 1E FF 2F E1 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 04 00 00 1A 38 00 9F E5 00 00 9F E7 98 D1 F5 EB" inFile LIB_ARMV7

    // SubscriptionManager.CheckExpiryDate — subscriptions never expire (prologue -> bx lr).
    "F0 48 2D E9 FC 60 9F E5 00 40 A0 E1 01 50 A0 E1 06 60 8F E0 00 00 D6 E5 00 00 50 E3 0A 00 00 1A" asPatternTo
        "1E FF 2F E1 FC 60 9F E5 00 40 A0 E1 01 50 A0 E1 06 60 8F E0 00 00 D6 E5 00 00 50 E3 0A 00 00 1A" inFile LIB_ARMV7

    // SubscriptionManager.ProcessSubscriptionRewards — report one reward (prologue -> mov r0, #1 ; bx lr).
    "F0 4B 2D E9 18 D0 4D E2 84 42 9F E5 00 70 A0 E1 01 80 A0 E1" asPatternTo
        "01 00 A0 E3 1E FF 2F E1 84 42 9F E5 00 70 A0 E1 01 80 A0 E1" inFile LIB_ARMV7

    // PurchaseManager.get_Busy — never busy (prologue -> mov r0, #0 ; bx lr).
    "F0 48 2D E9 10 D0 4D E2 70 51 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 0D 00 00 1A" asPatternTo
        "00 00 A0 E3 1E FF 2F E1 70 51 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 0D 00 00 1A" inFile LIB_ARMV7
}

private val iapSites: HexPatchBuilder.() -> Unit = {
    iapSitesArm64()
    iapSitesArmv7()
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

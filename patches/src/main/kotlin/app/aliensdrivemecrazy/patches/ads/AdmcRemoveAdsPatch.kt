package app.aliensdrivemecrazy.patches.ads

import app.aliensdrivemecrazy.patches.shared.AbiSites
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import app.aliensdrivemecrazy.patches.shared.NativeSite
import app.aliensdrivemecrazy.patches.shared.applyNativeSites
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Aliens Drive Me Crazy 3.2.11 — Remove ads (native il2cpp, both ABIs)
 *
 * Kills every interstitial ("commercial break") path while leaving the reward
 * dispatcher untouched for the Instant rewards patch:
 *
 *   1. `MyIronSource.ShowInterstitial`          arm64 file 0x1842AE0 / arm32 0xDB0408
 *   2. `MyIronSource.LoadIntersitial`           arm64 file 0x1842940 / arm32 0xDB020C
 *        (typo is the game's)
 *   3. `MyIronSource.CzyMozeBycInterstitial`    arm64 file 0x18427D8 / arm32 0xDB0098
 *        returns bool -> must return 0, a bare `ret` would return `this` != 0 = true
 *   4. `MyAds.PokazInterstital` (legacy)        arm64 file 0x183BDFC / arm32 0xDA7F74
 *
 * ### 3.2.11 retarget
 *
 * 3.2.10 → 3.2.11 (`versionCode 50` → `52`) only touched the interstitial wrapper and the
 * rewarded-video entry point. `ShowInterstitial` and `LoadIntersitial` are byte-identical
 * to 3.2.10, `MyAds.PokazInterstital` too; `CzyMozeBycInterstitial` grew the new
 * `interstitialContinuation` / `showingInterstitial` fields and therefore has a new
 * prologue, so its anchor was re-derived from the 3.2.11 dump (9 words / 36 B, 1 hit).
 *
 * ### Why two ABI anchor sets
 *
 * The `.xapk` ships `lib/arm64-v8a/libil2cpp.so` (A64) **and**
 * `lib/armeabi-v7a/libil2cpp.so` (A32, plain ARM — not Thumb). Both are patched, so the
 * resulting APK is safe to install on 64-bit and 32-bit devices alike. On arm64 the
 * il2cpp executable LOAD segment is mapped at `file = RVA - 0x4000`, on arm32
 * `file = RVA`; the anchors below are file offsets in the shipped 3.2.11 libs and each
 * occurs exactly once per library.
 *
 * Replacement is the classic IL2CPP early-out:
 *   - arm64: `mov w0, #0 ; ret` (`00 00 80 52 C0 03 5F D6`), remaining verified words NOP'd
 *   - arm32: `mov r0, #0 ; bx lr` (`00 00 A0 E3 1E FF 2F E1`), remaining verified bytes kept
 *
 * The old prologue becomes dead code after the injected return, and nothing outside the
 * verified window is ever written.
 */
@Suppress("unused")
val admcRemoveAdsPatch = rawResourcePatch(
    name = "Remove ads",
    description = "Stops all interstitial/commercial-break ads between games (native libil2cpp.so patch for both arm64-v8a and armeabi-v7a; rewarded videos are handled by the instant-rewards patch).",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADMC)

    execute {
        applyNativeSites(
            patchName = "ADMC Remove ads (3.2.11)",
            abiSites = listOf(
                AbiSites(
                    abi = "arm64-v8a",
                    sites = listOf(
                        // MyIronSource.ShowInterstitial(Action) @ file 0x1842AE0
                        NativeSite(
                            label = "MyIronSource.ShowInterstitial",
                            verifyHex = "FE5FBDA9F65701A9F44F02A9D6EA00D077D9009075D90090",
                            replacementHex = "00008052C0035FD61F2003D51F2003D51F2003D51F2003D5",
                        ),
                        // MyIronSource.LoadIntersitial @ file 0x1842940
                        NativeSite(
                            label = "MyIronSource.LoadIntersitial",
                            verifyHex = "FE0F1CF8F85F01A9F65702A9F44F03A9D3EA00D014D900D0",
                            replacementHex = "00008052C0035FD61F2003D51F2003D51F2003D51F2003D5",
                        ),
                        // MyIronSource.CzyMozeBycInterstitial (returns bool) @ file 0x18427D8
                        NativeSite(
                            label = "MyIronSource.CzyMozeBycInterstitial (bool)",
                            verifyHex = "FE0F1DF8F65701A9F44F02A9D6EA00D075D9009014D90090" +
                                "C8A27F39B5B645F9942644F9",
                            replacementHex = "00008052C0035FD61F2003D51F2003D51F2003D5" +
                                "1F2003D51F2003D51F2003D51F2003D5",
                        ),
                        // MyAds.PokazInterstital (legacy Unity-Ads path) @ file 0x183BDFC
                        NativeSite(
                            label = "MyAds.PokazInterstital (legacy)",
                            verifyHex = "FE0F1DF8F65701A9F44F02A916EB00B0",
                            replacementHex = "00008052C0035FD61F2003D51F2003D5",
                        ),
                    ),
                ),
                AbiSites(
                    abi = "armeabi-v7a",
                    sites = listOf(
                        // MyIronSource.ShowInterstitial(Action) @ file 0xDB0408
                        NativeSite(
                            label = "MyIronSource.ShowInterstitial",
                            verifyHex = "70402DE978609FE50050A0E10140A0E106608FE0" +
                                "0000D6E5000050E30700001A60009FE500009FE7A0B8F1EB58009FE5",
                            replacementHex = "0000A0E31EFF2FE10050A0E10140A0E106608FE0" +
                                "0000D6E5000050E30700001A60009FE500009FE7A0B8F1EB58009FE5",
                        ),
                        // MyIronSource.LoadIntersitial @ file 0xDB020C
                        NativeSite(
                            label = "MyIronSource.LoadIntersitial",
                            verifyHex = "F0482DE908D04DE2B4419FE504408FE0",
                            replacementHex = "0000A0E31EFF2FE1B4419FE504408FE0",
                        ),
                        // MyIronSource.CzyMozeBycInterstitial (bool) @ file 0xDB0098
                        NativeSite(
                            label = "MyIronSource.CzyMozeBycInterstitial (bool)",
                            verifyHex = "70402DE9C4509FE50040A0E105508FE00000D5E5" +
                                "000050E30A00001AB0009FE500009FE77DB9F1EB",
                            replacementHex = "0000A0E31EFF2FE10040A0E105508FE00000D5E5" +
                                "000050E30A00001AB0009FE500009FE77DB9F1EB",
                        ),
                        // MyAds.PokazInterstital (legacy) @ file 0xDA7F74
                        NativeSite(
                            label = "MyAds.PokazInterstital (legacy)",
                            verifyHex = "30482DE984509FE50040A0E105508FE00000D5E5" +
                                "000050E30700001A70009FE500009FE7C6D9F1EB",
                            replacementHex = "0000A0E31EFF2FE10040A0E105508FE00000D5E5" +
                                "000050E30700001A70009FE500009FE7C6D9F1EB",
                        ),
                    ),
                ),
            ),
        )
    }
}

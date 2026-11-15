package app.aliensdrivemecrazy.patches.money

import app.aliensdrivemecrazy.patches.shared.AbiSites
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import app.aliensdrivemecrazy.patches.shared.NativeSite
import app.aliensdrivemecrazy.patches.shared.applyNativeSites
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Aliens Drive Me Crazy 3.2.11 — Unlimited coins (native il2cpp, both ABIs)
 *
 * The game is Unity IL2CPP: all coin logic lives in `libil2cpp.so`, the DEX layer is only
 * SDK glue.
 *
 * `Org.Awake` loads the saved coin total and stores it into `Org.TotalCoins`:
 *
 *   arm64-v8a  @ file 0x16FE0B8 (VA 0x1701FB8, `Org.Awake` VA 0x1701F00):
 *       bc be 04 94   bl  Load.LoadInt          (VA 0x1831C44, PlayerPrefs int loader)
 *       68 03 40 f9   ldr x8, [x27]
 *       60 2a 00 b9   str w0, [x19, #0x28]      <- Org.TotalCoins (field 0x28)
 *
 *   armeabi-v7a @ file 0xC02A4C (VA 0xC02A4C, `Org.Awake` VA 0xC028C0):
 *       8f 4c 06 eb   bl  Load.LoadInt          (VA 0xD95C74)
 *       a4 16 9f e5   ldr r1, [pc, #0x6a4]
 *       01 10 9f e7   ldr r1, [pc, r1]
 *       18 00 84 e5   str r0, [r4, #0x18]       <- Org.TotalCoins (field 0x18 on A32)
 *
 * Patch: replace the `bl Load.LoadInt` with a constant load, so every coin save-read
 * returns 1,342,177,279 (`0x4FFFFFFF`):
 *
 *   arm64: mov w0, #0x4fffffff   -> `00 00 b6 12` (a single word: MOVN w0,#0xB000,lsl 16)
 *   arm32: mvn r0, #0xb0000000   -> `b0 04 e0 e3` (a single word)
 *
 * Only the `bl` word is rewritten; the following `ldr`/`str` (and the `x8`/`r1` staging
 * the *next* LoadInt call depends on) stay untouched.
 *
 * ### Why the field offset differs per ABI
 *
 * IL2CPP lays out class fields separately for each architecture: in the 3.2.11
 * `Il2CppDumper` output `Org.TotalCoins` is `0x28` on arm64-v8a but `0x18` on armeabi-v7a.
 * The anchors below are the actual stored code paths, so both agree with their own layout.
 *
 * ### 3.2.11 retarget
 *
 * `Org.Awake` moved from file 0x16FDB88 (3.2.10) to 0x16FE0B8 (`bl` site) and the IL2CPP
 * executable LOAD segment still maps `file = RVA - 0x4000` on arm64 (arm32 `file = RVA`).
 * Both anchors occur exactly once in their library, which makes the match self-verifying —
 * if Rebel Twins ships a new build the anchor moves and the patch fails loudly instead of
 * corrupting the library.
 */
@Suppress("unused")
val admcUnlimitedCoinsPatch = rawResourcePatch(
    name = "Unlimited coins",
    description = "Coin save reads always return ~1.34 billion coins (native libil2cpp.so patch on Org.TotalCoins for both arm64-v8a and armeabi-v7a).",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADMC)

    execute {
        applyNativeSites(
            patchName = "ADMC Unlimited coins (3.2.11)",
            abiSites = listOf(
                AbiSites(
                    abi = "arm64-v8a",
                    sites = listOf(
                        // [bl Load.LoadInt][ldr x8,[x27]][str w0,[x19,#0x28]] @ file 0x16FE0B8
                        NativeSite(
                            label = "Org.Awake -> Org.TotalCoins (field 0x28)",
                            verifyHex = "E3BE0494680340F9602A00B9",
                            replacementHex = "0000B612680340F9602A00B9",
                        ),
                    ),
                ),
                AbiSites(
                    abi = "armeabi-v7a",
                    sites = listOf(
                        // [bl Load.LoadInt] @ file 0xC02A4C, storing to [r4,#0x18]
                        NativeSite(
                            label = "Org.Awake -> Org.TotalCoins (field 0x18)",
                            verifyHex = "884C06EBA4169FE5",
                            replacementHex = "B004E0E3A4169FE5",
                        ),
                    ),
                ),
            ),
        )
    }
}

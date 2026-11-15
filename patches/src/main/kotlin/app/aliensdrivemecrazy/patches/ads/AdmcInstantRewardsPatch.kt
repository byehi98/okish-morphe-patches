package app.aliensdrivemecrazy.patches.ads

import app.aliensdrivemecrazy.patches.shared.AbiSites
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import app.aliensdrivemecrazy.patches.shared.NativeSite
import app.aliensdrivemecrazy.patches.shared.applyNativeSites
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Aliens Drive Me Crazy 3.2.11 — Instant rewards (native il2cpp, both ABIs)
 *
 * Reward buttons (`btnAdmoRew.OnClick`, `BtnWatchAds.OnClick`) call
 * `MyIronSource.ShowRewardAds(MyIronSource.RewardType Ktora)`, which normally:
 *
 *   - checks ad availability, updates the "minutes since last ad" timer,
 *   - runs the pre-ad block (icall + float `timeScale` math, pauses/rewinds the game),
 *   - shows the IronSource rewarded video,
 *   - lets the ad SDK callback reach `RewardedVideoAdRewardedEvent`, which reads
 *     `JakaNagroda` (`0` → `NagrodaRevive` coroutine, `1` → `NagrodaDubleCoins`) and
 *     tail-calls `StartCoroutine` on the calling (main) thread.
 *
 * Patch: overwrite the first 2 instructions of `ShowRewardAds` with a tail-call:
 *
 *   str <Ktora>, [this + JakaNagroda]   ; arm64 `str w1,[x0,#0x24]` / arm32 `str r1,[r0,#0x14]`
 *   b   RewardedVideoAdRewardedEvent    ; grant instantly, no ad
 *
 * This is deliberately an entry *tail-branch* (not a mid-method BL):
 *   - `this` passes through untouched in x0/r0 — the dispatcher needs it to read
 *     `JakaNagroda` and to be the coroutine host.
 *   - No BL → x30/lr is never clobbered → the dispatcher returns straight to
 *     `ShowRewardAds`' caller. A mid-method BL + RET self-loops because the dispatcher's
 *     epilogue restores the return address to the RET itself.
 *   - No stack push/pop games: the dispatcher's own prologue/epilogue stay balanced, and
 *     because our stub replaces `ShowRewardAds`' own prologue there is nothing to unwind.
 *   - Skips the availability gate, timer update, pre-ad icall/float block (which pauses
 *     `timeScale`, so the reward coroutine could never run → soft-lock) and the ad itself.
 *
 * `JakaNagroda` is a **per-ABI field offset**: `0x24` on arm64-v8a, `0x14` on armeabi-v7a
 * (IL2CPP lays out class fields separately per architecture). Writing the arm64 offset on
 * arm32 would clobber `interstitialContinuation`/`showingInterstitial` instead.
 *
 * ### 3.2.11 retarget
 *
 * `ShowRewardAds` moved from file 0x1846C0C (3.2.10) to **0x18431D8** and its prologue
 * changed, so a fresh 8-word anchor was derived (1 hit in the 56 MB library). The
 * dispatcher `RewardedVideoAdRewardedEvent` moved to file 0x183ED10 (arm64) / 0xDAB9C8
 * (arm32); the branch displacement is unchanged (-0x44CC on arm64), so the encoded `b`
 * word is the same as in the 3.2.10 patch. Both callers still pass the reward type in
 * w1/r1: revive = 0, double coins = 1 (verified in the 3.2.11 disassembly).
 */
@Suppress("unused")
val admcInstantRewardsPatch = rawResourcePatch(
    name = "Instant rewards",
    description = "Reward buttons (free revive, double coins) pay out instantly without playing any ad video (native libil2cpp.so patch for both arm64-v8a and armeabi-v7a).",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADMC)

    execute {
        applyNativeSites(
            patchName = "ADMC Instant rewards (3.2.11)",
            abiSites = listOf(
                AbiSites(
                    abi = "arm64-v8a",
                    sites = listOf(
                        // MyIronSource.ShowRewardAds entry @ file 0x18431D8.
                        // words 0-1 -> str w1,[x0,#0x24] ; b RewardedVideoAdRewardedEvent
                        // (0x183ED10; b @ 0x18431DC, delta -0x44CC, imm26 -0x1133).
                        // Tail words 2-7 are kept byte-for-byte (unreachable dead code).
                        NativeSite(
                            label = "MyIronSource.ShowRewardAds -> RewardedVideoAdRewardedEvent",
                            verifyHex = "FE0F1DF8F65701A9F44F02A9D6EA00B015D900B0" +
                                "F303012AC8CE7F39B51646F9",
                            replacementHex = "012400B9CDEEFF17F44F02A9D6EA00B015D900B0" +
                                "F303012AC8CE7F39B51646F9",
                        ),
                    ),
                ),
                AbiSites(
                    abi = "armeabi-v7a",
                    sites = listOf(
                        // MyIronSource.ShowRewardAds entry @ file 0xDB0C04 (A32).
                        // words 0-1 -> str r1,[r0,#0x14] ; b RewardedVideoAdRewardedEvent
                        // (0xDAB9C8; b @ 0xDB0C08, ARM PC = +8, delta -0x5248, imm24 -0x1492).
                        // Replaces the `push {r4,r5,r6,r7,fp,lr}` + `sub sp,sp,#8` prologue,
                        // tail words are kept byte-for-byte (unreachable dead code).
                        NativeSite(
                            label = "MyIronSource.ShowRewardAds -> RewardedVideoAdRewardedEvent",
                            verifyHex = "F0482DE908D04DE22C629FE50050A0E1",
                            replacementHex = "141080E56EEBFFEA2C629FE50050A0E1",
                        ),
                    ),
                ),
            ),
        )
    }
}

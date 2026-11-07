package app.frlegends.patches.ads

import app.frlegends.patches.shared.Constants.COMPATIBILITY_FR_LEGENDS
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * FR Legends 0.4.9 · **Free Ad Rewards** — every "watch video" button pays out instantly.
 *
 * This is a *reward* patch, not an ad-block: the reward and close callbacks are invoked the
 * moment the game asks for an ad, so the payout path runs exactly as it would after a real
 * video, but no ad is requested and there is no network wait. Three native sites:
 *
 * | # | Symbol | File offset | Edit |
 * |---|---|---|---|
 * | 1 | `AdsManager.ShowAds(Action reward, Action close)` | `0x12F2E04` | 52 B stub: invoke both |
 * | 2 | `RewardedAdManager.ShowRewardedAd(Action success, Action close)` | `0x12F31A8` | 52 B stub: invoke both |
 * | 3 | `AdsManager.IsWaitEnough()` | `0x12F2884` | `mov w0, #1 ; ret` |
 *
 * ### Why both `ShowAds` and `ShowRewardedAd`
 *
 * `AdsManager.ShowAds` is the high-level entry used by the UI (it checks readiness and the
 * cooldown), while `RewardedAdManager.ShowRewardedAd` is the manager that actually talks to
 * the ad network. Some reward buttons call the manager directly (garage "double reward",
 * post-race bonus), so patching only one of them leaves part of the UI waiting on an ad that
 * never arrives. Both are stubbed with the same null-safe shape:
 *
 * ```
 * stp  x30, x19, [sp, #-0x20]!
 * stp  x20, x21, [sp, #0x10]
 * mov  x19, x1                  ; reward / success
 * mov  x20, x2                  ; close
 * cbz  x19, +0x8
 * mov  x0, x19
 * bl   System.Action.Invoke     ; 0x1FE4E70
 * cbz  x20, +0x8
 * mov  x0, x20
 * bl   System.Action.Invoke     ; 0x1FE4E70
 * ldp  x20, x21, [sp, #0x10]
 * ldp  x30, x19, [sp], #0x20
 * ret
 * ```
 *
 * Both callbacks are optional in the original signatures (the game frequently passes only
 * a reward or only a close action), hence the `cbz` guards. The `BL` immediates are
 * PC-relative, so each stub must stay at its own address — the two bodies are identical but
 * the encoded call targets are not.
 *
 * ### Cooldown
 *
 * `AdsManager.IsWaitEnough()` is the 300-second / 12-video gate that greys out reward tiles.
 * Forcing it to `true` makes the buttons usable at any time without touching the counters.
 *
 * ### Interaction with "Remove Ads"
 *
 * The two patches touch disjoint byte ranges and can be enabled together: "Remove Ads"
 * removes banners/billboards/interstitials, this patch makes the *voluntary* rewarded tiles
 * pay out. Each pattern occurs exactly once in the 0.4.9 library and the spans do not overlap.
 */
private const val LIB = "lib/arm64-v8a/libil2cpp.so"

private val freeAdRewardSites: HexPatchBuilder.() -> Unit = {
    // AdsManager.ShowAds @ file 0x12F2E04: pay the reward, then close.
    "FE 57 BE A9 F4 4F 01 A9 F3 03 02 AA F4 03 01 AA F5 03 00 AA 10 00 00 94 E0 00 00 36 E1 03 14 AA E2 03 13 AA F4 4F 41 A9 E0 03 15 AA FE 57 C2 A8 18 00 00 14" asPatternTo
    "FE 4F BE A9 F4 57 01 A9 F3 03 01 AA F4 03 02 AA 73 00 00 B4 E0 03 13 AA 15 B8 33 94 74 00 00 B4 E0 03 14 AA 12 B8 33 94 F4 57 41 A9 FE 4F C2 A8 C0 03 5F D6" inFile LIB

    // RewardedAdManager.ShowRewardedAd @ file 0x12F31A8: same instant payout for direct calls.
    "FE 0F 1D F8 F6 57 01 A9 F4 4F 02 A9 D6 D7 00 B0 C8 AA 69 39 F3 03 02 AA F4 03 01 AA F5 03 00 AA 28 01 00 37 A0 C6 00 B0 00 54 42 F9 7A E2 FC 97 A0 C6 00 D0" asPatternTo
    "FE 4F BE A9 F4 57 01 A9 F3 03 01 AA F4 03 02 AA 73 00 00 B4 E0 03 13 AA 2C B7 33 94 74 00 00 B4 E0 03 14 AA 29 B7 33 94 F4 57 41 A9 FE 4F C2 A8 C0 03 5F D6" inFile LIB

    // AdsManager.IsWaitEnough @ file 0x12F2884: reward tiles always available.
    "FF 43 01 D1 FE 5F 02 A9 F6 57 03 A9 F4 4F 04 A9 D4 D7 00 D0 88 E2 67 39" asPatternTo
    "20 00 80 52 C0 03 5F D6 F6 57 03 A9 F4 4F 04 A9 D4 D7 00 D0 88 E2 67 39" inFile LIB
}

@Suppress("unused")
val freeAdRewardsPatch = rawResourcePatch(
    name = "Free Ad Rewards",
    description = "The \"watch a video\" buttons give you the reward straight away. Tap one and " +
        "the cash, gold or bonus is yours — no video, no waiting, and the buttons are always " +
        "ready to use.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FR_LEGENDS)

    dependsOn(hexPatch(block = freeAdRewardSites))
}

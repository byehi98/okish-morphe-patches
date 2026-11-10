package app.sonicdash.patches.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch
import app.sonicdash.patches.shared.Constants.COMPATIBILITY_SONIC_DASH

/**
 * Sonic Dash Run 10.7.0 · **Free Ad Rewards** — every "watch a video" offer pays out
 * immediately, with no video and no network round-trip.
 *
 * This is a *reward* patch, not another ad-block. The single patched method is
 * `AdvertHarnessOverlay.ActivateRewardedVideo(Action success, Action fail, string placement)`,
 * a static method that stores the two delegates on the harness singleton, asks the ad
 * mediator for a provider and then starts the "wait for advert to show" coroutine. When a
 * video finishes, the game invokes the stored success delegate; the patch simply invokes it
 * up front and returns, so the payout continuation runs exactly as it would after a real
 * video.
 *
 * 10.7.0 ships both ABIs, and each carries its own copy of the method, so both are stubbed
 * (file offsets from `analysis/sonicdash/notes/10.7.0-patch-sites.md`):
 *
 * | ABI | library | RVA | file offset | stub size |
 * |---|---|---|---|---|
 * | arm64-v8a | `lib/arm64-v8a/libil2cpp.so` | `0x11F113C` | `0x11ED13C` (`RVA − 0x4000`) | 28 B |
 * | armeabi-v7a | `lib/armeabi-v7a/libil2cpp.so` | `0x8E983C` | `0x8E983C` (`RVA`) | 32 B |
 *
 * ### The arm64 stub
 *
 * ```
 * stp  x30, x19, [sp, #-0x10]!
 * mov  x19, x0                 ; success callback
 * cbz  x19, +0xC               ; null-safe
 * mov  x0, x19
 * bl   System.Action.Invoke    ; RVA 0x1A98574, BL @0x11F114C = 0x94229D0A
 * ldp  x30, x19, [sp], #0x10
 * ret
 * ```
 *
 * ### The armv7 stub (A32 / ARM, not Thumb)
 *
 * ```
 * push {r4, lr}
 * mov  r4, r0                  ; success callback
 * cmp  r4, #0
 * popeq {r4, pc}               ; null-safe
 * mov  r0, r4
 * bl   System.Action.Invoke    ; RVA 0x1327D30, BL @0x8E9850 = 0xEB28F936
 * pop  {r4, pc}
 * nop                          ; pads the 32-byte site; the anchor is 4 B longer
 * ```
 *
 * Both `BL`s are PC-relative and were recomputed for 10.7.0, so each stub must stay at its
 * site (10.7.0 moved both the method and `System.Action.Invoke`). `System.Action.Invoke`
 * itself is a tail-call shim (`br x2` / `bx r2`), which is why `x0` / `r0` must hold the
 * `Action` instance. The rest of the original method (provider lookup +
 * `WaitForAdvertToShow`) becomes unreachable dead code.
 *
 * ### Why invoking the success delegate is enough
 *
 * `ContentCostAdWatch.ApplyCost` builds the success delegate from its compiler-generated
 * display-class method and tail-branches into `ActivateRewardedVideo`. That callback is the
 * real payout continuation: it notifies the ad visibility manager and rewarded-video status,
 * sends the reward analytics and finally invokes the transaction's `onCompleted` delegate
 * with `-1` (success). Running it directly grants the reward without ever touching an ad
 * provider, and it is not affected by the paid-user predicates patched by "Remove Ads", so
 * the reward tiles stay visible when both patches are used together.
 *
 * ### Verification
 *
 * Both anchors occur exactly once in their library, and the replacements are the same length
 * as the anchors (28/28 arm64, 32/32 armv7). `hexPatch` throws if an anchor stops matching.
 * `ignoreMissingTargetFiles = true` keeps single-ABI bundles working — a bundle that ships
 * only one split simply has no file at the other library path.
 *
 * ### Version support
 *
 * 10.7.0 only. 10.6.0 is no longer supported: the method moved and its prologue, the anchor
 * bytes and both `BL` displacements changed, so the 10.6.0 patterns do not match this build.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libil2cpp.so"
private const val LIB_ARM32 = "lib/armeabi-v7a/libil2cpp.so"

private val freeAdRewardSites: HexPatchBuilder.() -> Unit = {
    // arm64-v8a — AdvertHarnessOverlay.ActivateRewardedVideo @ file 0x11ED13C: run the
    // success continuation immediately instead of waiting for an ad to play.
    "FE 67 BC A9 F8 5F 01 A9 F6 57 02 A9 F4 4F 03 A9 55 B5 00 F0 37 9F 00 B0 F6 03 02 AA" asPatternTo
    "FE 4F BF A9 F3 03 00 AA 73 00 00 B4 E0 03 13 AA 0A 9D 22 94 FE 4F C1 A8 C0 03 5F D6" inFile LIB_ARM64

    // armeabi-v7a — same method @ 0x8E983C: same immediate payout, ARM (A32) encoding.
    "F0 41 2D E9 78 61 9F E5 00 50 A0 E1 02 70 A0 E1 01 40 A0 E1 06 60 8F E0 00 00 D6 E5 00 00 50 E3" asPatternTo
    "10 40 2D E9 00 40 A0 E1 00 00 54 E3 10 80 BD 08 04 00 A0 E1 36 F9 28 EB 10 80 BD E8 00 00 A0 E1" inFile LIB_ARM32
}

@Suppress("unused")
val freeAdRewardsPatch = rawResourcePatch(
    name = "Free Ad Rewards",
    description = "The \"watch a video\" buttons give you the reward straight away. Tap one and " +
        "the rings, red star rings, gems or unlock are yours — no video and no waiting.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SONIC_DASH)

    dependsOn(hexPatch(ignoreMissingTargetFiles = true, block = freeAdRewardSites))
}

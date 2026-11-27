package app.sonicdash.patches.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch
import app.sonicdash.patches.shared.Constants.COMPATIBILITY_SONIC_DASH

/**
 * Sonic Dash Run 10.8.0 · **Free Ad Rewards** — every "watch a video" offer pays out
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
 * 10.8.0 ships both ABIs, and each carries its own copy of the method, so both are stubbed:
 *
 * | ABI | library | RVA | file offset | stub size |
 * |---|---|---|---|---|
 * | arm64-v8a | `lib/arm64-v8a/libil2cpp.so` | `0x11F12E4` | `0x11ED2E4` (`RVA − 0x4000`) | 28 B |
 * | armeabi-v7a | `lib/armeabi-v7a/libil2cpp.so` | `0x8E992C` | `0x8E992C` (`RVA`) | 36 B |
 *
 * ### The arm64 stub
 *
 * ```
 * stp  x30, x19, [sp, #-0x10]!
 * mov  x19, x0                 ; success callback
 * cbz  x19, +0xC               ; null-safe
 * mov  x0, x19
 * bl   System.Action.Invoke    ; RVA 0x1A98AE8, BL @0x11F12F4 = 0x94229DFD
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
 * ldr  r2, [r4, #0xC]          ; invoke target
 * ldr  r1, [r4, #0x14]         ; method
 * ldr  r0, [r4, #0x20]         ; target object
 * blx  r2
 * pop  {r4, pc}
 * ```
 *
 * The `System.Action.Invoke` body IL2CPP emits is a generic delegate tail-call shim
 * (`ldr r2, [r0, #0xC] ; ldr r1, [r0, #0x14] ; ldr r0, [r0, #0x20] ; bx r2`) that appears
 * 200+ times in the library, once per delegate type. Rather than pinning the stub to one
 * relocated copy, 10.8.0 inlines the four instructions; the semantics are identical and the
 * stub no longer depends on any address inside the library.
 *
 * `System.Action.Invoke` itself is a tail-call shim (`br x2` / `bx r2`), which is why
 * `x0` / `r0` must hold the `Action` instance. The rest of the original method (provider
 * lookup + `WaitForAdvertToShow`) becomes unreachable dead code.
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
 * Both anchors occur exactly once in their library. The arm64 replacement is 28 B / 28 B;
 * the armv7 anchor is extended to 48 B so the zero-padded 36 B stub keeps the match unique
 * and the 12 trailing bytes (dead lazy-init code) are blanked safely behind the injected
 * `pop {r4, pc}`. `hexPatch` throws if an anchor stops matching.
 * `ignoreMissingTargetFiles = true` keeps single-ABI bundles working — a bundle that ships
 * only one split simply has no file at the other library path.
 *
 * ### Version support
 *
 * 10.8.0 only. 10.7.0 is no longer supported: the method moved and its prologue, the anchor
 * bytes and the arm64 `BL` displacement changed, so the 10.7.0 patterns do not match this
 * build.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libil2cpp.so"
private const val LIB_ARM32 = "lib/armeabi-v7a/libil2cpp.so"

private val freeAdRewardSites: HexPatchBuilder.() -> Unit = {
    // arm64-v8a — AdvertHarnessOverlay.ActivateRewardedVideo @ file 0x11ED2E4: run the
    // success continuation immediately instead of waiting for an ad to play.
    "FE 67 BC A9 F8 5F 01 A9 F6 57 02 A9 F4 4F 03 A9 75 B5 00 90 37 9F 00 B0 F6 03 02 AA" asPatternTo
    "FE 4F BF A9 F3 03 00 AA 73 00 00 B4 E0 03 13 AA FD 9D 22 94 FE 4F C1 A8 C0 03 5F D6" inFile LIB_ARM64

    // armeabi-v7a — same method @ 0x8E992C: same immediate payout, ARM (A32) encoding with
    // the delegate invoke shim inlined. The 48-byte anchor keeps the match unique.
    "F0 41 2D E9 78 61 9F E5 00 50 A0 E1 02 70 A0 E1 01 40 A0 E1 06 60 8F E0 00 00 D6 E5 00 00 50 E3 10 00 00 1A 5C 01 9F E5 00 00 9F E7 7F 4F F9 EB" asPatternTo
    "10 40 2D E9 00 40 A0 E1 00 00 54 E3 10 80 BD 08 0C 20 94 E5 14 10 94 E5 20 00 94 E5 32 FF 2F E1 10 80 BD E8" inFile LIB_ARM32
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

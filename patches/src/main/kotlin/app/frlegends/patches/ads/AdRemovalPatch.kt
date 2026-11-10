package app.frlegends.patches.ads

import app.frlegends.patches.shared.Constants.COMPATIBILITY_FR_LEGENDS
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * FR Legends 0.4.9 · **Remove Ads** — banners, billboards and interstitials are disabled
 * natively, and the game's own "ad finished" continuation still fires.
 *
 * Same shape as [app.frlegends.patches.billing.freeStorePurchasesPatch]: static edits to
 * `lib/arm64-v8a/libil2cpp.so`, DEX untouched. Four sites, each with a byte pattern that
 * occurs exactly once in the 0.4.9 library:
 *
 * | # | Symbol | File offset | Edit |
 * |---|---|---|---|
 * | 1 | `AdsMix.IsBannerEnabled(int)` | `0x12F4DC0` | `mov w0, #0 ; ret` |
 * | 2 | `AdsMix.TryAddInGameAdsLooper(Camera)` | `0x12F5D08` | `ret` |
 * | 3 | `InterstitialAdManager.TryShow(Action)` | `0x12F94AC` | stub: invoke `onFinished`, return |
 * | 4 | `InterstitialAdManager.EnsureLoaded()` | `0x12F9394` | `ret` |
 *
 * ### Why `TryShow` is stubbed instead of nopped
 *
 * `TryShow(Action onFinished)` is not fire-and-forget: the caller passes a continuation that
 * resumes the game after the full-screen ad closes. Returning early without invoking it would
 * leave race results / garage transitions waiting forever. The 36-byte stub therefore calls
 * the callback exactly the way the real "ad dismissed" path would:
 *
 * ```
 * str  x30, [sp, #-0x20]!
 * stp  x20, x19, [sp, #0x10]
 * mov  x19, x1                 ; onFinished
 * cbz  x19, +0x8               ; null-safe
 * mov  x0, x19
 * bl   System.Action.Invoke    ; 0x1FE4E70 (NOT UnityEvent.Invoke)
 * ldp  x20, x19, [sp, #0x10]
 * ldr  x30, [sp], #0x20
 * ret
 * ```
 *
 * `System.Action.Invoke` is the correct target: these callbacks are plain `Action` delegates,
 * not `UnityEvent`s. The `BL` immediate is position-dependent, so the stub stays in place at
 * `0x12FD4AC`.
 *
 * `EnsureLoaded()` only starts downloading/preparing an interstitial; after `TryShow` is
 * stubbed there is nothing to show, so returning immediately also removes the ad SDK's
 * background network traffic and its memory cost.
 *
 * Banner and billboard calls do not need a callback: `IsBannerEnabled` is a predicate the
 * layout code consults before creating a banner view, and `TryAddInGameAdsLooper` only
 * schedules the in-race Adverty/Gadsme billboard spawner. Forcing the predicate false and
 * turning the spawner into a no-op removes both ad surfaces without affecting gameplay.
 */
private const val LIB = "lib/arm64-v8a/libil2cpp.so"

private val adRemovalSites: HexPatchBuilder.() -> Unit = {
    // AdsMix.IsBannerEnabled @ file 0x12F4DC0: always false.
    "FE 57 BE A9 F4 4F 01 A9 D4 D7 00 90 88 2A 68 39" asPatternTo
    "00 00 80 52 C0 03 5F D6 D4 D7 00 90 88 2A 68 39" inFile LIB

    // AdsMix.TryAddInGameAdsLooper @ file 0x12F5D08: no billboard spawner.
    "FE 57 BE A9 F4 4F 01 A9 B5 D7 00 F0 B4 C6 00 90" asPatternTo
    "C0 03 5F D6 F4 4F 01 A9 B5 D7 00 F0 B4 C6 00 90" inFile LIB

    // InterstitialAdManager.TryShow @ file 0x12F94AC: invoke onFinished, show nothing.
    "FE 0F 1C F8 F8 5F 01 A9 F6 57 02 A9 F4 4F 03 A9 94 D7 00 F0 95 C6 00 90 88 DE 68 39 B5 A2 46 F9 F6 03 01 AA" asPatternTo
    "FE 0F 1E F8 F4 4F 01 A9 F3 03 01 AA 73 00 00 B4 E0 03 13 AA 6C 9E 33 94 F4 4F 41 A9 FE 07 42 F8 C0 03 5F D6" inFile LIB

    // InterstitialAdManager.EnsureLoaded @ file 0x12F9394: stop preloading.
    "FE 0F 1D F8 F6 57 01 A9 F4 4F 02 A9 94 D7 00 F0 88 DA 68 39 F3 03 00 AA" asPatternTo
    "C0 03 5F D6 F6 57 01 A9 F4 4F 02 A9 94 D7 00 F0 88 DA 68 39 F3 03 00 AA" inFile LIB
}

@Suppress("unused")
val removeAdsPatch = rawResourcePatch(
    name = "Remove Ads",
    description = "Stops banners, roadside billboards and full-screen ads from appearing. " +
        "Races and menus carry on as normal — the ads just never show up.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FR_LEGENDS)

    dependsOn(hexPatch(block = adRemovalSites))
}

package app.magicsort.ads

import app.magicsort.shared.Constants.COMPATIBILITY_MAGIC_SORT
import app.magicsort.shared.NativeAnchor
import app.magicsort.shared.applyLibil2cppEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Magic Sort 0.0.3420 — **Instant Rewarded Ads** (static native patch).
 *
 * Rewarded ads are opt-in and are *not* gated by `HasNoAds`, which is why they keep
 * working after the Remove Ads patch. But under Remove Ads the ad SDK is never
 * initialised, so `AdService._rewarded` stays `null` and the stock rewarded flow
 * either null-references or hangs waiting for a fill that can never arrive. This
 * patch makes the app's own success path run unconditionally, so every
 * "watch ad for X" surface pays out instantly with no video.
 *
 * Two anchors, both required — the patch aborts if either is absent.
 *
 * ── 1. `AdService.<ShowRewarded>d__40.MoveNext` @ `0x1B61B80` ─────────────
 * The state machine's provider-read guard. Verified words:
 * ```
 * 0x1B61B7C  ldr  x22, [x22, #0xd98]     ; rewarded-provider slot
 * 0x1B61B80  cbz  w8,  #0x1B61BDC        ; <- REPLACED (first-run / not-initialised path)
 * 0x1B61B84  cbz  x23, #0x1B61D88
 * 0x1B61B88  ldr  x20, [x23, #0x18]
 * 0x1B61B8C  cbz  x20, #0x1B61D44
 *            ... provider IsReady / Show path ...
 * ```
 * At `0x1B61B80`, `x19` is already the state-machine `this` (the success tail at
 * `0x1B61D40` stores into `[x19], #8`), and `x22` has already been loaded from
 * `[x22, #0xd98]` at `0x1B61B7C`, after the metadata-init guard. Every instruction
 * from `0x1B61B84` on is a provider-nullability guard, and `0x1B61B88` dereferences
 * `x23` — exactly the dereference that NREs when the ad SDK was never initialised
 * (the normal state under Remove Ads). Replacing the 4-byte `cbz w8` with
 * `b 0x1B61D40` (`70 00 00 14`) jumps straight to the existing success tail:
 *
 * ```
 * 0x1B61D40  mov  w20, #1                          ; result = true
 * 0x1B61D44  mov  w8, #-2                          ; <>1__state = finished
 * 0x1B61D48  str  w8, [x19], #8                    ; store state, advance builder ptr
 *            ...
 *            bl   AsyncTaskMethodBuilder<bool>.SetResult(..., true)
 *            epilogue + ret
 * ```
 *
 * i.e. the `Task<bool>` completes with `true` without ever executing the provider
 * guard chain above (the `ldr x20,[x23,#0x18]` at `0x1B61B88` is exactly the
 * dereference that NREs when the ad SDK was never initialised). Because the
 * original conditional branch is replaced by an unconditional one, the hook is
 * inert only if the anchor bytes do not match (in which case the whole patch
 * fails, it does not silently ship).
 *
 * ── 2. `AdService.IsRewardedReady()` @ `0x1B5F73C` ────────────────────────
 * ```
 * 0x1B5F73C  str  x20, [sp, #-0x20]!
 * 0x1B5F740  stp  x19, x30, [sp, #0x10]
 * 0x1B5F744  adrp x20, ...
 * 0x1B5F748  ldrb w8, [x20, ...]
 * 0x1B5F74C  mov  x19, x0
 * 0x1B5F750  tbnz w8, #0, ...
 * ```
 * `LifeRewardedButton.Show()` → `ShowLoadingBarIfRewardedNotReady()` calls this and
 * shows an endless spinner when the rewarded provider is null — the normal state
 * under Remove Ads. Overwriting the prologue with `mov w0, #1 ; ret`
 * (`20 00 80 52 C0 03 5F D6`) makes readiness report `true`, so the offer button
 * stays usable and the loading bar never appears. This is the swampattack2-proven
 * pattern: the stub returns before touching the stack, so the discarded frame setup
 * never executes and there is no `sp` imbalance or link-register leak.
 *
 * ── Why the reward becomes instant on *every* surface ─────────────────────
 * The callers were xref-checked and all call `AdService.ShowRewarded` (`0x1B5F494`)
 * directly with no readiness gate: `LifeRewardedButton.<ShowRewardedToEarnLife>d__8`
 * (`0x16E96E8`), `Rewarded2xButton`, `PlayOnManager.TryUseWithRewarded`,
 * `WatchAndWinController` and the daily `RewardedButton`. `ShowRewarded`
 * unconditionally starts this state machine, so hook 1 covers all of them.
 * `Rewarded.IsReady` (`0x1B642BC`) has zero xrefs (dead code) and needs no patch.
 *
 * Rewards are granted 100 % locally: `LifeRewardedButton` simply does
 * `if (await ShowRewarded()) InventoryService.Earn(...)` (`0x16E93EC`), a pure local
 * model write with no server round-trip. Nothing validates that an ad was watched.
 *
 * Deliberately **compatible with Remove Ads**. The naive alternative — forcing
 * `mov w20, #1` at the grant site `0x1B61CD4` — still requires the real ad SDK to
 * reach that instruction, so it does nothing once ads are disabled. Branching to the
 * tail at `0x1B61D40` does not.
 */
@Suppress("unused")
val instantRewardedPatch = rawResourcePatch(
    name = "Instant Rewarded Ads",
    description = "Every watch-ad offer grants its reward instantly with no video.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAGIC_SORT)

    execute {
        applyLibil2cppEdits(
            patchName = "Instant Rewarded Ads",
            // Both anchors are mandatory: the branch alone would still leave the
            // offer button stuck behind an endless loading bar, and the readiness
            // stub alone would not grant anything (ShowRewarded would still NRE).
            anchors = listOf(SHOW_REWARDED_SUCCESS_TAIL, IS_REWARDED_READY),
        )
    }
}

/**
 * `AdService.<ShowRewarded>d__40.MoveNext` @ `0x1B61B80`:
 * `cbz w8,#0x1B61BDC` → `b 0x1B61D40` (the success tail that sets the
 * `Task<bool>` result to `true`).
 *
 * Verify window (24 B, one hit in 95,548,936 B):
 * `e8 02 00 34` = `cbz w8,#0x1B61BDC` (the instruction we replace),
 * `37 10 00 b4` = a `cbz x23` guard, `f4 0e 40 f9` = `ldr x20,[x23,#0x18]`,
 * `d4 0d 00 b4` = a `cbz x20` guard, then an `adrp`/`ldr` pair. The surrounding
 * words exist only to make the locator unique — only the first 4 bytes are written.
 */
private val SHOW_REWARDED_SUCCESS_TAIL = NativeAnchor(
    label = "AdService.ShowRewarded -> branch to success tail (task completes true, no provider deref)",
    rva = 0x1B61B80,
    fileOffset = 0x1B61B80,
    verifyHex = "e8020034371000b4f40e40f9d40d00b46adb0190880240f9",
    replacementHex = "70000014",
)

/**
 * `AdService.IsRewardedReady()` @ `0x1B5F73C` → `true`.
 *
 * Verify window (24 B, one hit in 95,548,936 B):
 * `f4 0f 1e f8` = `str x20,[sp,#-0x20]!`, `f3 7b 01 a9` = `stp x19,x30,[sp,#0x10]`,
 * `34 fe 01 f0` + `88 ba 6c 39` = the `adrp`/`ldrb` config read,
 * `f3 03 00 aa` = `mov x19,x0`, `c8 00 00 37` = `tbnz w8,...`.
 */
private val IS_REWARDED_READY = NativeAnchor(
    label = "AdService.IsRewardedReady -> true (no endless loading bar on watch-ad offers)",
    rva = 0x1B5F73C,
    fileOffset = 0x1B5F73C,
    verifyHex = "f40f1ef8f37b01a934fe01f088ba6c39f30300aac8000037",
    replacementHex = "20008052c0035fd6",
)

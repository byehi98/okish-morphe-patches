package app.magicsort.ads

import app.magicsort.shared.AbiAnchorSet
import app.magicsort.shared.Constants.COMPATIBILITY_MAGIC_SORT
import app.magicsort.shared.NativeAnchor
import app.magicsort.shared.applyLibil2cppEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Magic Sort 0.0.3536 — **Instant Rewarded Ads** (static native patch, both ABIs).
 *
 * Rewarded ads are opt-in and are *not* gated by `HasNoAds`, which is why they keep
 * working after the Remove Ads patch. But under Remove Ads the ad SDK is never
 * initialised, so the rewarded provider stays `null` and the stock rewarded flow
 * either null-references or hangs waiting for a fill that can never arrive. This
 * patch makes the app's own success path run unconditionally, so every
 * "watch ad for X" surface pays out instantly with no video.
 *
 * Two anchors per ABI, both required — the patch aborts if either is absent.
 *
 * ── 1. `AdService.<ShowRewarded>d__40.MoveNext` — jump straight to the success tail ──
 *
 * arm64-v8a @ `0x1857010` (success tail `0x18571D0`):
 * ```
 * 0x1857004  ldr  w8,  [x19]            ; <>1__state
 * 0x1857008  ldr  x23, [x19, #0x20]
 * 0x185700C  ldr  x22, [x22, #0x548]    ; rewarded-provider slot
 * 0x1857010  cbz  w8,  #0x185706C       ; <- REPLACED (first-run / not-initialised path)
 * 0x1857014  cbz  x23, #0x1857218
 * 0x1857018  ldr  x20, [x23, #0x18]     ; <- NREs when the ad SDK never initialised
 * 0x185701C  cbz  x20, #0x18571D4
 * ```
 * Replacing the 4-byte `cbz w8` with `b 0x18571D0` (`70 00 00 14`) jumps to the
 * existing success tail:
 * ```
 * 0x18571D0  mov  w20, #1               ; result = true
 * 0x18571D4  mov  w8,  #-2              ; <>1__state = finished
 * 0x18571D8  str  w8,  [x19], #8
 *            ... AsyncTaskMethodBuilder<bool>.SetResult(..., true)
 * ```
 *
 * armeabi-v7a @ `0x1136FF8` (completion block `0x1137234`) — ARM/A32 mode:
 * ```
 * 0x1136FF0  ldr  r1, [r4]              ; <>1__state
 * 0x1136FF4  ldr  r7, [r4, #0x10]
 * 0x1136FF8  cmp  r1, #0                ; <- REPLACED by `mov r5,#1`
 * 0x1136FFC  beq  #0x1137068            ; <- REPLACED by `b 0x1137234`
 * 0x1137000  cmp  r7, #0
 *            ... provider deref ldr r5,[r7,#0xc] ...
 * 0x1137014  beq  #0x1137080            ; provider null -> completes FALSE
 * ```
 * The 8-byte replacement `mov r5, #1 ; b 0x1137234`
 * (`01 00 50 E3` / `8C 00 00 EA`) enters the shared completion block with the
 * result register already `true`. That block stores `<>1__state = -2`, advances to
 * `<>t__builder` and calls `SetResult`, so `Task<bool>` completes `true` without
 * ever touching the provider chain (the `ldr r5,[r7,#0xc]` dereference is exactly
 * what NREs when the ad SDK was never initialised).
 *
 * Because the original conditional branch is replaced by an unconditional one, the
 * hook is inert only if the anchor bytes do not match (in which case the whole patch
 * fails, it does not silently ship).
 *
 * ── 2. `AdService.IsRewardedReady()` — always ready ────────────────────────
 *
 * arm64-v8a @ `0x1854B54`, armeabi-v7a @ `0x11342D4`.
 * `LifeRewardedButton.Show()` → `ShowLoadingBarIfRewardedNotReady()` calls this and
 * shows an endless spinner when the rewarded provider is null — the normal state
 * under Remove Ads. Overwriting the prologue with `mov w0,#1 ; ret`
 * (`20 00 80 52 C0 03 5F D6`) / `mov r0,#1 ; bx lr` (`01 00 A0 E3 1E FF 2F E1`)
 * makes readiness report `true`, so the offer button stays usable and the loading
 * bar never appears. This is the swampattack2-proven pattern: the stub returns
 * before touching the stack, so the discarded frame setup never executes and there
 * is no `sp` imbalance or link-register leak.
 *
 * ── Why the reward becomes instant on *every* surface ─────────────────────
 * The callers all call `AdService.ShowRewarded` directly with no readiness gate:
 * `LifeRewardedButton.<ShowRewardedToEarnLife>d__8`, `Rewarded2xButton`,
 * `PlayOnManager.TryUseWithRewarded`, `WatchAndWinController` and the daily
 * `RewardedButton`. `ShowRewarded` unconditionally starts this state machine, so
 * hook 1 covers all of them. `Rewarded.IsReady` has zero xrefs (dead code).
 *
 * Rewards are granted 100 % locally: `LifeRewardedButton` simply does
 * `if (await ShowRewarded()) InventoryService.Earn(...)`, a pure local model write
 * with no server round-trip. Nothing validates that an ad was watched.
 *
 * Deliberately **compatible with Remove Ads**: forcing the grant site alone still
 * requires the real ad SDK to reach it, so it does nothing once ads are disabled.
 * Branching to the completion tail does not.
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
            // Both anchors are mandatory per ABI: the branch alone would still leave
            // the offer button stuck behind an endless loading bar, and the readiness
            // stub alone would not grant anything (ShowRewarded would still NRE).
            anchorSets = listOf(
                AbiAnchorSet(
                    "arm64-v8a",
                    listOf(SHOW_REWARDED_SUCCESS_TAIL_ARM64, IS_REWARDED_READY_ARM64),
                ),
                AbiAnchorSet(
                    "armeabi-v7a",
                    listOf(SHOW_REWARDED_SUCCESS_TAIL_V7A, IS_REWARDED_READY_V7A),
                ),
            ),
        )
    }
}

/**
 * `AdService.<ShowRewarded>d__40.MoveNext` @ `0x1857010` (arm64-v8a, 0.0.3536):
 * `cbz w8,#0x185706C` → `b 0x18571D0` (the success tail that sets the
 * `Task<bool>` result to `true`; imm26 = (0x18571D0 − 0x1857010)/4 = 0x70).
 *
 * Verify window (24 B, one hit in 95,938,872 B):
 * `e8 02 00 34` = `cbz w8,#0x185706C` (the instruction we replace),
 * `37 10 00 b4` = a `cbz x23` guard, `f4 0e 40 f9` = `ldr x20,[x23,#0x18]`,
 * `d4 0d 00 b4` = a `cbz x20` guard, then an `adrp`/`ldr` pair. The surrounding
 * words exist only to make the locator unique — only the first 4 bytes are written.
 *
 * `MoveNext` itself starts at `0x1856F74`; the anchor sits after the lazy-init
 * block, at the `<>1__state` dispatch (`ldr w8,[x19]` / `ldr x23,[x19,#0x20]`).
 * The success tail at `0x18571D0` is `mov w20,#1` → `mov w8,#-2` →
 * `str w8,[x19],#8` → `AsyncTaskMethodBuilder<bool>.SetResult(..., true)`.
 */
private val SHOW_REWARDED_SUCCESS_TAIL_ARM64 = NativeAnchor(
    label = "AdService.ShowRewarded -> branch to success tail (task completes true, no provider deref)",
    rva = 0x1857010,
    fileOffset = 0x1857010,
    verifyHex = "e8020034371000b4f40e40f9d40d00b48af601b0880240f9",
    replacementHex = "70000014",
)

/**
 * `AdService.<ShowRewarded>d__40.MoveNext` @ `0x1136FF8` (armeabi-v7a, 0.0.3536):
 * `cmp r1,#0 ; beq 0x1137068` → `mov r5,#1 ; b 0x1137234`
 * (`00 00 51 E3` / `19 00 00 0A` → `01 50 A0 E3` / `8C 00 00 EA`;
 * A32 `b` imm24 = (0x1137234 − 0x1136FFC − 8)/4 = 0x8C).
 *
 * The 32-bit build has no single branch to the success tail (the tail is a shared
 * completion block that reads the result from `r5`), so the state compare itself is
 * replaced. `r4` (the state-machine `this`) is already live at this point
 * (`mov r4,r0` in the prologue) and the completion block sets `<>1__state = -2`,
 * advances `r4` to `<>t.__builder` and calls `SetResult(builder, r5)` with `r5 = 1`.
 *
 * Verify window (24 B, one hit in 86,299,136 B): the state compare/branch, the
 * `r7` null guard, its throw call, and the provider load `ldr r5,[r7,#0xc]`.
 */
private val SHOW_REWARDED_SUCCESS_TAIL_V7A = NativeAnchor(
    label = "AdService.ShowRewarded -> completion block with result true (no provider deref)",
    rva = 0x1136FF8,
    fileOffset = 0x1136FF8,
    verifyHex = "000051e31900000a000057e30000001afe1ce3eb0c5097e5",
    replacementHex = "0150a0e38c0000ea",
)

/**
 * `AdService.IsRewardedReady()` @ `0x1854B54` → `true` (arm64-v8a, 0.0.3536).
 *
 * Verify window (24 B, one hit in 95,938,872 B):
 * `f4 0f 1e f8` = `str x20,[sp,#-0x20]!`, `f3 7b 01 a9` = `stp x19,x30,[sp,#0x10]`,
 * `94 19 02 90` + `88 22 6c 39` = the `adrp`/`ldrb` config read,
 * `f3 03 00 aa` = `mov x19,x0`, `c8 00 00 37` = `tbnz w8,...`.
 */
private val IS_REWARDED_READY_ARM64 = NativeAnchor(
    label = "AdService.IsRewardedReady -> true (no endless loading bar on watch-ad offers)",
    rva = 0x1854B54,
    fileOffset = 0x1854B54,
    verifyHex = "f40f1ef8f37b01a99419029088226c39f30300aac8000037",
    replacementHex = "20008052c0035fd6",
)

/**
 * `AdService.IsRewardedReady()` @ `0x11342D4` → `true` (armeabi-v7a, 0.0.3536).
 *
 * Verify window (48 B, one hit in 86,299,136 B — the 24 B prologue prefix is *not*
 * unique in the 32-bit lib: it occurs 164 times, because `push {r4,r5,r11,lr}` +
 * `ldr rN,[pc,#…]` + `add rN,pc` is the standard IL2CPP metadata-init guard. The
 * window therefore extends over the null-path `bl` and the `ldrb`/`cmp`/`bne`
 * triple):
 * `30 48 2d e9` = `push {r4,r5,r11,lr}`, `a4 50 9f e5` = `ldr r5,[pc,#0xa4]`,
 * `00 40 a0 e1` = `mov r4,r0`, `05 50 8f e0` = `add r5,pc,r5`,
 * `00 00 d5 e5` = `ldrb r0,[r5]`, `00 00 50 e3` = `cmp r0,#0`,
 * `04 00 00 1a` = `bne`, then the lazy-init `ldr`/`ldr`/`bl`/`mov`/`strb` body.
 */
private val IS_REWARDED_READY_V7A = NativeAnchor(
    label = "AdService.IsRewardedReady -> true (no endless loading bar on watch-ad offers)",
    rva = 0x11342D4,
    fileOffset = 0x11342D4,
    verifyHex = "30482de9a4509fe50040a0e105508fe00000d5e5000050e30400001a90009fe500009fe7fb27e3eb0100a0e30000c5e5",
    replacementHex = "0100a0e31eff2fe1",
)

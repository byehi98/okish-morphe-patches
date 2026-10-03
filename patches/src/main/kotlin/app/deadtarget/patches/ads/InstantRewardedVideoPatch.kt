package app.deadtarget.patches.ads

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.183.0 (583009063) — Instant rewarded video.
 *
 * One site, one goal: **the reward is granted instantly, with no ad watched.** The "watch an
 * ad for a reward" popup is left completely alone — the game's own logic decides when it
 * appears, so it appears on its normal cadence and then goes away.
 *
 * Dead Target is Unity IL2CPP: the smali layer is SDK plumbing only, every ad decision lives
 * in `libil2cpp.so` (85,048,024 bytes, arm64-v8a, plaintext `.text`). Class `AdsController`
 * (`MonoBehaviour`, TypeDefIndex 3175, confirmed in `analysis/dead-target/dump/dump.cs`) owns
 * the whole rewarded flow.
 *
 * ============================================================================
 * REVISION HISTORY — why the availability gates were REMOVED
 * ============================================================================
 * An earlier build of this patch did two things: the tail-branch (kept, below) **plus** five
 * `AdsController` availability predicates forced to constant `true`, to satisfy the "always
 * report available even when no ad is downloaded" half of the request. Device testing killed
 * the second half:
 *
 *   > "when there is ad there is a popup to watch the ad and get rewards — the rewards do
 *   >  work but the popup is appearing again and again"
 *
 * That is a precise diagnostic and it exonerates the tail-branch: the reward *does* arrive,
 * so the stub is correct. The bug is entirely in the gates. Those five predicates are not
 * "is an ad downloaded" — they **are** the game's own per-segment cooldown/availability
 * logic, so forcing them `true` made the popup ignore the very state that was supposed to
 * stop it re-prompting. Forcing availability here is self-defeating, because the gate and the
 * cooldown are the same decision, not two independent ones.
 *
 * The bookkeeping was never the problem, which is what made this confusing to debug: the
 * stubbed call *does* run the game's full completion path, including
 * `AdsController.UpdateVideoAdsStatus` → `IncreaseAdsWatchedCounter` /
 * `UpdateAdsWatchedInterval` / `ResetPlayingBattleCounter` (verified chain, see below). So the
 * game really was advancing its counters and the watch interval — and the forced-true
 * `CanShowVideoAds` was overriding that freshly-written state on the very next prompt.
 *
 * Hence: the five gates are **reverted**, and only the tail-branch remains. The original goal
 * is not abandoned, it is satisfied by the tail-branch alone — the gate never needs to report
 * "true" artificially, because when the popup *is* shown it is claimed without a single
 * ad-network call.
 *
 * ============================================================================
 * KNOWN TRADE-OFF, and the narrow fallback if it bites (NOT implemented here)
 * ============================================================================
 * What is traded away: if the genuine availability gate turns out to require an *ad actually
 * loaded by the network*, then with nothing loaded the popup might never appear at all. That
 * is the opposite failure — no prompt instead of an endless one.
 *
 * The argument that it will still appear: the user's own report — "when there is ad there is
 * a popup to watch the ad and get rewards" — describes the game's behaviour when an ad *is*
 * present, i.e. the unforced gate already passes in exactly the situation the popup is meant
 * for. Dropping the forced-true should restore the intended cadence rather than delete the
 * prompt. That is a prediction, not a measurement, hence this note.
 *
 * If testing instead shows the popup never appears, the narrower follow-up is to bypass only
 * the **network-level** availability and leave sites 1-5 alone — do **not** reach for the
 * game-side per-segment gates again, that is the bug being fixed. Note that the obvious
 * candidate, `MediationManager.IsAdsAvailable`, is shared across every `AD_TYPE`, so it
 * cannot simply be stubbed true (that would drag interstitials in too — see DELIBERATELY
 * EXCLUDED); a rewarded-video-scoped variant is required. Deliberately not written
 * speculatively: it should only be attempted after observing the real behaviour.
 *
 * ============================================================================
 * VA → FILE OFFSET: the +0x4000 trap
 * ============================================================================
 * ⚠️ **Il2CppDumper's `script.json` Address (and dump.cs's `VA:`) is a VIRTUAL address.**
 * Dead Target's executable LOAD segment maps `file 0x1C1273C -> VA 0x1C1673C`, i.e.
 * **delta = +0x4000**, so **file offset = VA − 0x4000**. Re-derived from the ELF program
 * headers for this file (PT_LOAD flags=R E, `p_offset=0x1c1273c`, `p_vaddr=0x1c1673c`,
 * `p_filesz=0x2f575e4`), and dump.cs's own `Offset:` column agrees at the patched site.
 * Same trap documented in ../unlock/UnlimitedCurrencyPatch.kt. Writing at the raw VA lands
 * **+16 KB past** the intended instruction and silently corrupts the library.
 *
 * ============================================================================
 * THE REAL FLOW (llvm-objdump 21.1.8, read out of the shipped lib)
 * ============================================================================
 * `AdsController.ShowRewardedVideoAds(SEGMENT_ID segmentId)` — VA 0x283FDE0:
 *   0x283FDE0  d10143ff  sub  sp, sp, #0x50      <- genuine entry (verified)
 *   0x283FDF8  2a0103f3  mov  w19, w1            <- segmentId
 *   0x283FDFC  aa0003f4  mov  x20, x0            <- this
 *   0x283FE28  2a1303e1  mov  w1, w19
 *   0x283FE2C  97fffadd  bl   0x283e9a0         <- AdsController.CanShowVideoAds (site 5)
 *   0x283FE30  36000680  tbz  w0, #0x0, ...     <- bail if the gate said no
 *   0x283FE3C  b9004a93  str  w19, [x20, #0x48]  <- this->_crtSegmentID = segmentId  (0x48)
 *   0x283FE68  52800061  mov  w1, #3            <- AD_TYPE.REWARDED_VIDEO
 *   0x283FE74  94001224  bl   0x2844704         <- MediationManager.Show  *** THE BLOCKER ***
 *   …and the ad, if one is not loaded, never calls back — so the reward never arrives.
 *
 * The patched site's job is precisely to skip from the entry straight past all of that to the
 * game's own completion handler, so the mediation layer is never reached and nothing needs to
 * be "available" for the reward to be granted.
 *
 * `AdsController.OnCompleteShow(bool isNeedSendLog = True)` — VA 0x28401F4, **same class**:
 *   0x28401F4  a9be57fe  stp  x30, x21, [sp,#-0x20]!   <- genuine entry, pre-indexed
 *   0x2840204  2a0103f4  mov  w20, w1            <- isNeedSendLog
 *   0x2840208  aa0003f3  mov  x19, x0            <- this
 *   0x2840230  f9401a68  ldr  x8, [x19, #0x30]   <- this->_adsCallback           (0x30)
 *   0x2840234  b4000468  cbz  x8, 0x28402c0      <- null guard: returns WITHOUT rewarding
 *   0x2840238  b9404a61  ldr  w1, [x19, #0x48]  <- this->_crtSegmentID           (0x48)
 *   0x284023C  97ffe852  bl   0x283a384          <- AdsCallbacks.OnRewardedVideoAdsCompleted
 *   …                                              *** REWARD GRANTED HERE ***
 *   0x2840248  9400001f  bl   0x28402c4           <- AdsController.UpdateVideoAdsStatus
 *   0x2840290  36000074  tbz  w20, #0x0, ...     <- if !isNeedSendLog, skip the log call
 *   0x2840298  9400001f  bl   0x2840314           <- AdsController.SendRewardedVideoAdsLogs
 *
 * and, inside `UpdateVideoAdsStatus(SEGMENT_ID segId = 29)` — VA 0x28402C4 — the three calls
 * that do the actual cooldown bookkeeping (each `bl` target re-decoded from the shipped lib):
 *
 *   0x28402e8  97ffff2b  bl   0x283ff94  -> AdsController.IncreaseAdsWatchedCounter(SEGMENT_ID)
 *   0x28402f4  97ffff5b  bl   0x2840060  -> AdsController.UpdateAdsWatchedInterval(SEGMENT_ID)
 *   0x2840300  97ffff8f  bl   0x284013c  -> AdsController.ResetPlayingBattleCounter(SEGMENT_ID)
 *
 * That verified chain is what makes the reverted gates a bug rather than a feature: our stub
 * runs the game's real bookkeeping (`UpdateVideoAdsStatus` at **0x28402C4**, which advances
 * `IncreaseAdsWatchedCounter`, `UpdateAdsWatchedInterval` and `ResetPlayingBattleCounter`),
 * and the old forced-true `CanShowVideoAds` threw that freshly-written state away on the next
 * prompt.
 *
 * Address hygiene note, because this chain was mis-read once: `UpdateVideoAdsStatus` is at
 * **0x28402C4**; `SendRewardedVideoAdsLogs` is a *different* method at **0x2840314** and is
 * reached from the separate `isNeedSendLog` branch at 0x2840298. Conflating the two makes it
 * look as though `UpdateVideoAdsStatus` sits at 0x2840314 — it does not. (dump.cs confirms
 * both: `public void UpdateVideoAdsStatus(SEGMENT_ID segId = 29)` @0x28402C4 and
 * `private void SendRewardedVideoAdsLogs()` @0x2840314.)
 *
 * Field identities (dump.cs, AdsController): `_adManager` 0x28, `_adsCallback` 0x30,
 * `_crtSegmentID` 0x48. `OnRewardedVideoAdsCompleted` at 0x283A384 is a genuine prologue
 * (`sub sp,sp,#0x40` / `stp x30,x21,[sp,#0x20]`).
 *
 * Branch arithmetic (independently re-derived from lib bytes, not taken on trust):
 *   branch word sits at VA 0x283FDE0 + 4 = **0x283FDE8** (word 1 of the entry)
 *   target OnCompleteShow = **0x28401F4**
 *   delta = 0x28401F4 − 0x283FDE8 = **0x40C** (1036) → imm26 = 0x40C >> 2 = **0x103** (259)
 *   encoded: `(0b000101 << 26) | 0x103` = 0x14000103 → LE `03 01 00 14`
 * `llvm-mc -triple=aarch64 --show-encoding "b #+0x40C"` independently returns `[0x03,0x01,0x00,0x14]`.
 * (The same sum reproduces the game's own `bl @0x284023C → 0x283A384`, i.e. −24248 bytes,
 * which confirms the encoder and the target.)
 *
 * ============================================================================
 * THE SITE — THE TAIL-BRANCH (the "instant reward")
 * ============================================================================
 * Overwrite the first 3 words of `ShowRewardedVideoAds` with:
 *
 *     0x283FDE0  01 48 00 B9  B9004801  str  w1, [x0, #0x48]   ; _crtSegmentID = segmentId
 *     0x283FDE4  E1 03 1F 2A  2A1F03E1  mov  w1, wzr          ; isNeedSendLog = false
 *     0x283FDE8  03 01 00 14  14000103  b    0x28401F4       ; OnCompleteShow  (reward!)
 *
 * Encoding provenance — all three words confirmed with `llvm-mc --show-encoding`:
 *  - `str w1,[x0,#72]` → `[0x01,0x48,0x00,0xb9]`. This is the game's own instruction
 *    `str w19,[x20,#72]` at 0x283FE3C (`93 4A 00 B9`) with Rt/Rn swapped to w1/x0 — the
 *    unsigned-offset STR imm12 scale for a 32-bit store is 4, so imm12 = 18 = 72 = 0x48.
 *    It reproduces the original `_crtSegmentID = segmentId` assignment exactly, one line
 *    earlier and without the dead setup.
 *  - `mov w1, wzr` → `[0xe1,0x03,0x1f,0x2a]` = **0x2A1F03E1**. Use this canonical encoding:
 *    an earlier hand-rolled attempt produced 0x2A0003E1, which differs in the ORR-immediate
 *    shift field (immr/imms). Both end up copying zero, but 0x2A1F03E1 is what the assembler
 *    emits and it is overwhelmingly what this game itself emits — the canonical word occurs
 *    **23,937 times** in `libil2cpp.so` (`mov w0,wzr` 18,083×, `mov w2,wzr` 24,009×) versus
 *    3,260× for the non-canonical form. Matching the platform's own codegen matters here
 *    precisely because this patch is *outside* the compiler's knowledge.
 *    `w1 = false` also stops `SendRewardedVideoAdsLogs` from firing (guarded by
 *    `tbz w20,#0` at 0x2840290) — there is no ad to report. The reward-side bookkeeping is
 *    deliberately **not** suppressed: `UpdateVideoAdsStatus` runs regardless, which is exactly
 *    why the cooldown the popup honours stays correct.
 *  - `b #+0x40C` → `[0x03,0x01,0x00,0x14]`, see arithmetic above.
 *
 * Why this shape (mirrors AliensDriveMeCrazy's `ShowRewardAds` tail-branch, whose KDoc
 * documents the same three pitfalls):
 *  - **`b`, never `bl`.** `bl` would clobber x30, and the dispatcher's own
 *    `stp x30,x21,[sp,#-0x20]!` / epilogue would then `ret` to *our* `bl`'s own address —
 *    an infinite self-loop. With `b`, x30 still holds `ShowRewardedVideoAds`' original caller,
 *    the dispatcher saves that value, its epilogue restores it, and control returns straight
 *    to the real caller. No `ret` of our own, so the branch target must be a full prologue.
 *  - **`OnCompleteShow`'s exits all unwind x30 correctly.** Verified against the shipped lib:
 *    the shared epilogue at 0x28402B4 is `ldp x20,x19,[sp,#0x10]` / `ldp x30,x21,[sp],#0x20`
 *    / `ret`, and the one other exit at 0x28402A4 does the same restore and then *tail-branches*
 *    (`b 0x28438dc`) instead of `ret`-ing — also fine, because x30 was already reloaded from
 *    the frame before the branch. So no exit path can strand the caller's return address.
 *  - **x0 (`this`) must pass through untouched.** Both words we emit either use x0 directly
 *    or leave it alone, and nothing between the entry and the `b` modifies it. Branching into
 *    a handler that dereferences `this` with the wrong pointer is an instant SIGSEGV.
 *  - **`x0` is safe here because the dispatcher is in the SAME class.** `OnCompleteShow` is
 *    `AdsController.OnCompleteShow` (TypeDefIndex 3175), not an `AdsCallbacks` method — so the
 *    `_crtSegmentID` (0x48) and `_adsCallback` (0x30) offsets it reads are the very offsets
 *    this site just wrote/owns. Had the dispatcher been on another class, the field offsets
 *    would not line up and this trick would be invalid.
 *  - **Entering at 0x28401F4 is exactly a managed call `OnCompleteShow(false)`.** It is a
 *    `public void OnCompleteShow(bool isNeedSendLog = True)` on the controller, i.e. an
 *    ordinary externally-callable entry point: everything from 0x28401F4 onward is the game's
 *    own unmodified code, so its register setup (`mov x19,x0`, `mov w20,w1`), its metadata
 *    init guard and its frame are all intact by construction. Nothing the original method
 *    relies on is established by `ShowRewardedVideoAds`' prologue, because the two share none.
 *  - **No stack push/pop games.** The discarded prologue allocated nothing that is still live;
 *    `OnCompleteShow` establishes its own frame. The stub is idempotent and stack-neutral, so
 *    no recursion guard is needed: if one of `OnCompleteShow`'s own callees ever re-entered
 *    `ShowRewardedVideoAds`, re-entry would simply re-run these same three instructions —
 *    same field write, same branch — and stay bounded by the game's own stack depth.
 *
 * ============================================================================
 * THE REVERTED SITES — recorded, NOT patched (see REVISION HISTORY for why)
 * ============================================================================
 * These five `AdsController` predicates were briefly stubbed to `mov w0,#1 ; ret`
 * (`20 00 80 52 | C0 03 5F D6` — the compiler's own constant-true shape, which occurs
 * 1,735 times in this library). All five are reverted:
 *
 *  | # | AdsController method         | VA        | File offset | Status           |
 *  |---|------------------------------|-----------|-------------|------------------|
 *  | 1 | IsRVAdEnable                 | 0x283E7F0 | 0x283A7F0   | REVERTED         |
 *  | 2 | IsPreloadRVAd                | 0x283E80C | 0x283A80C   | REVERTED         |
 *  | 3 | IsRewardedVideoAvailable     | 0x283E890 | 0x283A890   | REVERTED         |
 *  | 4 | CanShowAnyInListVideoAds     | 0x283E924 | 0x283A924   | REVERTED         |
 *  | 5 | CanShowVideoAds              | 0x283E9A0 | 0x283A9A0   | REVERTED         |
 *
 * Reason, uniformly: they are the game's per-segment cooldown/availability logic, so forcing
 * them true removed the cooldown and produced the endless popup reported from device testing.
 * Site 5 is the decisive case — it is the precheck `ShowRewardedVideoAds` itself calls at
 * 0x283FE2C, and unlike a trivial leaf such as `DSystem.IsGunUnlockV2` it has a **real body**
 * that genuinely evaluates that state, so stubbing it overwrote real decisions rather than
 * merely short-circuiting a constant.
 *
 * Their original 36-byte windows, kept so the revert is auditable and a future, *narrower*
 * follow-up does not have to re-derive them:
 *
 *  1 IsRVAdEnable   0x283A7F0  FE 0F 1F F8 08 20 40 F9 88 00 00 B4 00 41 40 39
 *                             FE 07 41 F8 C0 03 5F D6 BA 5A D9 97 FE 57 BE A9 F4 4F 01 A9
 *  2 IsPreloadRVAd  0x283A80C  FE 57 BE A9 F4 4F 01 A9 75 47 01 B0 D4 2B 01 D0 A8 D6 5A 39
 *                             94 56 46 F9 F3 03 00 AA C8 00 00 37 C0 2B 01 D0
 *  3 IsRVAvailable  0x283A890  FF 43 01 D1 FE 13 00 F9 F6 57 03 A9 F4 4F 04 A9 76 47 01 B0
 *                             15 2D 01 F0 C8 DA 5A 39 B5 D2 43 F9 F4 03 01 2A
 *  4 CanShowAnyList 0x283A924  FE 0F 1D F8 F6 57 01 A9 F4 4F 02 A9 A1 02 00 B4 28 0C 40 F9
 *                             F3 03 01 AA F4 03 00 AA E0 03 1F 2A 28 02 00 B4
 *  5 CanShowVideo  0x283A9A0  FF 83 01 D1 FE 13 00 F9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9
 *                             76 47 01 B0 14 2D 01 F0 C8 DE 5A 39 94 DE 44 F9
 *
 * (Note sites 1/2 are frameless leaves and 3/5 open with `sub sp,sp,#0x40`-class prologues,
 * which is why "overwrite the first 8 bytes" was safe for them — no stack was ever pushed by
 * the discarded words. That safety argument is moot now that they are not patched.)
 *
 * ============================================================================
 * THE ONE PATCHED SITE — 36-byte anchor UNIQUE (1 hit), 12-byte write window
 * ============================================================================
 *  | # | AdsController method        | VA        | File offset | Stub          |
 *  |---|-----------------------------|-----------|-------------|---------------|
 *  | 6 | ShowRewardedVideoAds        | 0x283FDE0 | 0x283BDE0   | 12B TAIL_REWARD|
 *
 * Original 36 bytes at file offset 0x283BDE0 (the anchor) → replacement:
 *
 *   6 ShowRewarded  0x283BDE0  FF 43 01 D1 FE 13 00 F9 F6 57 03 A9 F4 4F 04 A9 75 47 01 90
 *                             A8 FA 5A 39 F3 03 01 2A F4 03 00 AA 28 01 00 37
 *                   →           01 48 00 B9 E1 03 1F 2A 03 01 00 14
 *
 * Word 0 is a genuine entry, confirmed by llvm-objdump on the shipped lib: `d10143ff`
 * (`sub sp,sp,#0x50`). Word 0 is also where the *branch* word must NOT be, which is why the
 * stub is 12 bytes (3 words: str, mov, b) and not 8.
 *
 * 36 bytes is required, not gold-plating: counting first-12-byte-prologue occurrences at this
 * site gives **1,409 hits**. A shorter search would resolve to an arbitrary earlier match and
 * corrupt an unrelated function. The full 36-byte window is exactly **1 hit**, and that hit is
 * the documented file offset. The write window is 0x283BDE0..0x283BDEB.
 *
 * Also checked for collisions: the full 12-byte replacement occurs **0 times** in the shipped
 * library, so it cannot be confused with pre-existing code.
 *
 * ============================================================================
 * DELIBERATELY EXCLUDED
 * ============================================================================
 *  - **`MediationManager.IsAdsAvailable` — VA 0x2844640 / file 0x2840640 — NOT patched.**
 *    It is *tempting* (its 36-byte window is equally unique:
 *    `FE0F1DF8F65701A9F44F02A9364701F0C8CE5B39F30302AAF403012AF50300AAC8000037`) and
 *    forcing it true would look like the thorough version of this patch. It is in
 *    `MediationManager` (**TypeDefIndex 3184**), a *different class* from `AdsController`
 *    (3175), and it is shared across **every** `AD_TYPE`. Forcing it true would also mark
 *    interstitials available, so `ShowInterstitialAds` would attempt a real show with nothing
 *    loaded and never call back — a soft-lock for zero benefit, since this site's tail-branch
 *    already bypasses the entire mediation layer. Recorded here so a future maintainer
 *    knows it was evaluated rather than missed. It is, however, the correct *place* to look
 *    if the fallback in REVISION HISTORY is ever needed — provided a rewarded-video-scoped
 *    variant is found rather than a global stub.
 *  - **`AdsController.CanClaimVideAdsReward` — VA 0x283EFF0 / file 0x283AFF0 — NOT patched.**
 *    This is the once-per-day claim gate. Left alone deliberately, and for an additional reason
 *    now: it is another cooldown-style predicate, so forcing it true would re-introduce exactly
 *    the endless-prompt class of bug this patch just reverted. It is a ready-made add-on only
 *    if the user later wants repeated daily claims — an economy decision, not this patch's job.
 *  - No `MediationManager.Show`, no `AdsCallbacks.*`, no interstitial methods, no
 *    currency / gun / god-mode change. (Currency lives in
 *    ../unlock/UnlimitedCurrencyPatch.kt and is a separate patch.)
 *  - No armeabi-v7a branch: this XAPK ships **arm64-v8a ONLY** (base + UnityDataAssetPack.apk
 *    + config.arm64_v8a.apk), so a second anchor table would be dead code.
 *
 * ============================================================================
 * RUNTIME CAVEAT — say this plainly, and correctly
 * ============================================================================
 * `_adsCallback` (0x30) is loaded and null-checked at 0x2840230/0x2840234 immediately
 * before `bl 0x283a384` = `AdsCallbacks.OnRewardedVideoAdsCompleted(SEGMENT_ID)`
 * (dump.cs: `public void` on `AdsCallbacks`, TypeDefIndex 3163), so the function is about to
 * make a call through that field. The check at 0x2840234 is **not** a benign early return:
 *
 *   0x2840234  b4000468  cbz  x8, 0x28402c0      ; _adsCallback == null ?
 *   0x28402c0  97d9540c  bl   0x1e952f0          ; -> raise NullReferenceException
 *
 * Three more null checks in the same function (0x2840264, 0x284027c, 0x2840288) merge into
 * the same 0x28402c0 block — the standard IL2CPP null-check merge — and 0x1e952f0 contains
 * **no `ret` at all**: it ends with `adrp x0,0xc37000 / add x0,x0,#0xa96` (a string address)
 * plus a tail-`b 0x1ed2604`, i.e. it is `il2cpp_codegen_raise_null_reference_exception`, a
 * **noreturn** thrower. (By contrast the genuinely benign `if (x == null) return;` checks
 * here — 0x2840250 and 0x28402a0 — branch to the shared epilogue at 0x28402b4 instead.)
 *
 * So if `_adsCallback` is null this path **raises a managed NullReferenceException** rather
 * than silently doing nothing. That is the same exception the *unpatched* game would raise
 * for a null callback, so it is faithful to the original semantics rather than a new
 * failure mode — but it is a throw, not a no-op, and worth stating plainly.
 *
 * In practice `_adsCallback` is populated by `AdsController.InitSdk()` (VA 0x283B684) at
 * game start-up regardless of whether any ad is *downloaded* or *available*, so it is
 * non-null in normal play. Net boundary of this patch:
 *  - "ad video not downloaded / not loaded" → **fully handled for the reward**: the stub never
 *    touches the mediation layer, so no ad is needed at any point to be paid. Whether the
 *    popup *offers* itself is the game's own decision, on its own cooldown.
 *  - "ads SDK not yet initialised" → **not** handled; the tap raises a NullReferenceException.
 *
 * No timing/race window is involved: the tail-branch is unconditional and synchronous, so it
 * cannot resolve "sometimes works" — if the popup appears, tapping it always pays instantly.
 * Note also that entering `OnCompleteShow` at its own entry point is exactly equivalent to the
 * SDK calling `OnCompleteShow(false)` on the controller — every instruction after 0x28401F4 is
 * the game's own unmodified code, so all of its register setup and frame handling is intact by
 * construction.
 *
 * ============================================================================
 * DELIVERY — how the patched .so lands in the XAPK split pipeline
 * ============================================================================
 * Same shape as ../unlock/UnlimitedCurrencyPatch.kt, which shipped and device-verified on
 * this same XAPK (see its DELIVERY section and Dead Trigger's NativeIapVerifierBypass
 * KDoc for the full Javap read-writeup): `PatchEngine` runs `ApkMerger.merge()` FIRST, so
 * base + `UnityDataAssetPack.apk` + `config.arm64_v8a.apk` become ONE merged APK before any
 * patch executes; the config split carries `lib/arm64-v8a/libil2cpp.so`. A `rawResourcePatch`
 * forces `ResourceMode.RAW_ONLY`, so `get(path, true)` resolves the raw-extracted
 * `lib/<abi>/libil2cpp.so`. The write here is SAME-LENGTH in-place (12-over-12) so
 * `detectFileChanges()` catches it on `lastModified` → `ApkUtils.applyTo` →
 * `signWithLegacyFallback`.
 *
 * Static byte edit (chosen) over a runtime companion `.so`: `.text` is plaintext on disk,
 * there is no `.so` integrity / signature / anti-tamper check in the chain, and the whole
 * bundle is re-signed as one unit — so this needs no NDK, no companion `.so`, no
 * `System.loadLibrary` trigger and no `mprotect` dance, and nothing is touched at runtime
 * before or after Unity's native init.
 *
 * Discovery: a top-level `val … = rawResourcePatch(…)` IS a public static field of type
 * Patch, which is exactly what `PatchLoader` scans, so list-patches exposes it. One concern
 * → plain top-level `val`, **no** `dependsOn` (a nested patch would live only inside the
 * parent's `dependencies` set — reachable, but invisible to discovery).
 */
@Suppress("unused")
val instantRewardedVideoPatch = rawResourcePatch(
    name = "Instant rewarded video",
    description = "Reward buttons pay out instantly. Tap once and you get the reward, with no ad to watch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        // arm64-v8a only — this XAPK has no armeabi-v7a split.
        applyAnchors(get("lib/arm64-v8a/libil2cpp.so", true), ARM64_ANCHORS)
    }
}

/**
 * One guarded byte-range replacement in `libil2cpp.so`.
 *
 * [anchorHex] is the ORIGINAL 36-byte function window (must occur exactly ONCE in the
 * library — that uniqueness is what makes the match self-verifying); [replacementHex] is the
 * stub written over the first bytes of that window.
 */
private class Anchor(
    /** Human label used in logs and [PatchException] messages. */
    val label: String,
    /** VIRTUAL address from Il2CppDumper's script.json (kept for messages only). */
    val va: Long,
    /** Documented exact file offset = va − 0x4000 (exec LOAD delta, see KDoc). */
    val fileOffset: Long,
    /** Hex of the ORIGINAL 36-byte window — must occur exactly once, or the patch aborts. */
    val anchorHex: String,
    /** Hex of the replacement written at that window's first bytes (12 bytes). */
    val replacementHex: String,
)

/**
 * `str w1,[x0,#0x48] ; mov w1,wzr ; b OnCompleteShow` — set `_crtSegmentID` from the
 * argument, then jump straight into the completion handler that dispatches the reward and
 * advances the game's watch/cooldown bookkeeping. No ad is requested, no mediation call is
 * made, and no availability gate is touched.
 */
private const val TAIL_REWARD = "014800B9E1031F2A03010014"

private val ARM64_ANCHORS = listOf(
    Anchor(
        label = "AdsController.ShowRewardedVideoAds -> set _crtSegmentID, branch to OnCompleteShow",
        va = 0x283FDE0,
        fileOffset = 0x283BDE0,
        anchorHex =
            "FF4301D1FE1300F9F65703A9" +
            "F44F04A975470190A8FA5A39" +
            "F303012AF40300AA28010037",
        replacementHex = TAIL_REWARD,
    ),
)

/**
 * Applies every [Anchor] to [lib], guarded by a unique-anchor search.
 *
 * Two-phase so a bad build fails with **zero** bytes written:
 *  1. the library is slurped once and every 36-byte anchor is searched for, requiring
 *     **exactly one** occurrence (12 bytes alone is hopelessly ambiguous here — 1,409 hits at
 *     the patched site — and a wrong hit would corrupt an unrelated function);
 *  2. each resolved offset is re-read through a [RandomAccessFile], compared against the
 *     original bytes, and only then overwritten.
 *
 * Each write is the same length as the bytes it replaces (12-over-12), so the patcher's
 * `lastModified`-keyed change diff picks it up. Throws [PatchException] with full context if
 * an anchor is missing or ambiguous, or if the bytes on disk are not what we expect — i.e.
 * new game build, unsupported version.
 */
private fun applyAnchors(lib: File, anchors: List<Anchor>) {
    println("Dead Target instant rewarded video: patching ${lib.name} (${lib.length()} bytes)")
    val bytes = lib.readBytes()

    // Phase 1 — resolve every anchor, or abort before touching anything.
    val writes = anchors.map { anchor ->
        val needle = hex(anchor.anchorHex)
        val replacement = hex(anchor.replacementHex)
        val hits = indexOfAll(bytes, needle)
        when {
            hits.isEmpty() -> throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — 36-byte anchor not found in " +
                    "${lib.name} (size=${bytes.size}). Expected file offset 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}). " +
                    "Unsupported app version?",
            )

            hits.size > 1 -> throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — 36-byte anchor is AMBIGUOUS " +
                    "(${hits.size} occurrences: " +
                    hits.joinToString(", ") { "0x" + it.toString(16) } +
                    "). Refusing to guess — unsupported app version?",
            )
        }
        val at = hits[0].toLong()
        if (at + replacement.size > bytes.size) {
            throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — resolved file offset 0x" +
                    "${at.toString(16)} is past end of ${lib.name} (size=${bytes.size}) — " +
                    "app layout changed?",
            )
        }
        val original = needle.copyOf(replacement.size)
        if (!bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size).contentEquals(original)) {
            throw PatchException(
                "Dead Target instant rewarded video: ${anchor.label} — anchor mismatch at VA 0x" +
                    "${anchor.va.toString(16)} (file 0x${at.toString(16)}): expected " +
                    "${toHex(original)} vs found " +
                    "${toHex(bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size))}. " +
                    "libil2cpp.so layout changed — unsupported app version?",
            )
        }
        if (at != anchor.fileOffset) {
            // Not fatal — the unique anchor is authoritative — but the .so moved, so say so
            // loudly instead of silently shipping.
            println(
                "Dead Target instant rewarded video: ${anchor.label} — NOTE: unique anchor resolved " +
                    "to 0x${at.toString(16)}, documented file offset is 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}).",
            )
        }
        Triple(anchor, at, replacement)
    }

    // Phase 2 — guarded in-place writes.
    RandomAccessFile(lib, "rw").use { raf ->
        for ((anchor, at, replacement) in writes) {
            val original = hex(anchor.anchorHex).copyOf(replacement.size)
            raf.seek(at)
            val actual = ByteArray(replacement.size)
            raf.readFully(actual)
            if (!actual.contentEquals(original)) {
                throw PatchException(
                    "Dead Target instant rewarded video: ${anchor.label} — original bytes changed " +
                        "between resolve and write at file 0x${at.toString(16)}: expected " +
                        "${toHex(original)} vs found ${toHex(actual)}.",
                )
            }
            raf.seek(at)
            raf.write(replacement)
            println(
                "Dead Target instant rewarded video: VA 0x${anchor.va.toString(16)} " +
                    "(file 0x${at.toString(16)}), ${replacement.size}-byte stub: " +
                    "${toHex(original)} -> ${anchor.replacementHex}  [${anchor.label}]",
            )
        }
    }
}

/** Every offset at which [needle] occurs in [haystack]. */
private fun indexOfAll(haystack: ByteArray, needle: ByteArray): List<Int> {
    if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
    val hits = mutableListOf<Int>()
    var i = 0
    val last = haystack.size - needle.size
    while (i <= last) {
        var j = 0
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) {
            hits.add(i)
            i += needle.size // anchors cannot overlap themselves
        } else {
            i += j + 1
        }
    }
    return hits
}

/** Parses a plain hex string (no separators) into bytes. */
private fun hex(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
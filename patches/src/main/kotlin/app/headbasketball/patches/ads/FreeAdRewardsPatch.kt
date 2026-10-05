package app.headbasketball.patches.ads

import app.headbasketball.patches.shared.AbiEdits
import app.headbasketball.patches.shared.Constants.COMPATIBILITY_HEAD_BASKETBALL
import app.headbasketball.patches.shared.NativeAnchor
import app.headbasketball.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Head Basketball 4.6.4 · **N3′ + N4 + N7 — Free ad rewards** (every "watch ad" button pays out
 * instantly, with no ad, no "failed to load advertisement" popup, and no waiting).
 * Unity **2022.3.62f3 / IL2CPP** (`com.dnddream.HeadBasketball`, versionCode 471).
 * Three static in-place byte edits of the shipped `lib/arm64-v8a/libil2cpp.so` — two 8-byte
 * and one 4-byte write, each over a 32-byte-verified window. No companion `.so`, no DEX change,
 * no ad-SDK involvement of any kind.
 *
 * ============================================================================
 * THIS IS *NOT* AN AD-BLOCK — read this before "simplifying" anything
 * ============================================================================
 * The obvious way to remove ads is to stop the ad from showing. **In this game that
 * deletes content**, and the inventory proves it:
 *
 * ```
 * ironSource API                     RVA         callers  game callers
 * IronSource.showRewardedVideo()     0x17B235C   2        1  — AdsManager.<ShowRewarded>d__19::MoveNext @ 0x1ECC4C0
 * IronSource.showInterstitial()      0x17B2690   1        0  — only IronSourceDemoScript::OnGUI (SDK's bundled demo, unused)
 * IronSource.showOfferwall()         0x17B24A4   1        0  — demo only
 * displayBanner() / hideBanner()     —          0        0
 * ```
 *
 * There is **exactly one ad-show call in the whole codebase** and it is a *rewarded*
 * video. There are **ZERO interstitials**. Every one of the 8 `ShowAds` entry points is
 * an opt-in **rewarded** ad, so suppressing the show without running the game's own
 * completion handler would take away:
 *  * the free Death-mode entry  (`AdsType.ENTRY_DEATH`, sites 3 and 5),
 *  * the free Headcup entry     (`AdsType.ENTRY_HEADCUP`, sites 4 and 6),
 *  * the shop "watch ad → get fee points" payout (`SHOP_WATCH_ADS_AND_GET_FEE_POINT`,
 *    site 2 → `UIPopupRewardShare.PopupReceive(2×fee)` → `DataHelper.IncreaseTotalPoint`),
 *  * the cosmetic unlock claims (3 sites).
 *
* So the whole point of this patch is the difference between
* **"suppress the show and drop the reward"** (a naive ad-block, which deletes content)
* and **"skip the show, still run the game's own completion handler"** (N3). The game's
* `FinishAds()` *is* that completion handler: it restores BGM/effect volume and runs the
* `AdsType` switch that grants the reward. We call it directly and never touch the SDK.
* N7's only job is to make sure the game actually *reaches* that path — see below.
 *
 * ============================================================================
 * THE DEVICE BUG — WHY N3 ALONE WAS DEAD CODE (and why N7 is now mandatory)
 * ============================================================================
 * N3 + N4 shipped and **did not work**: the user tapped "watch ad" and the game said
 * **"failed to load advertisement"**. No reward, and the popup every time. Root-caused
 * on-device and re-confirmed statically:
 *
 * `AdsManager::ShowAds(AdsType)` shows **no** ad and **no** UI itself. It only asks the
 * SDK whether a rewarded video is available, and returns that answer:
 * ```
 * 1ecb838: bl   0x17ae3ec   ; IronSource.get_Agent()
 * 1ecb83c: cbz  x0, 0x1ecb930     ; agent null -> throw path
 * 1ecb840: mov  x1, xzr
 * 1ecb844: bl   0x17b22b8   ; IronSource.isRewardedVideoAvailable()   <-- THE GATE
 * 1ecb848: mov  w20, w0
 * 1ecb84c: tbz  w0, #0x0, 0x1ecb91c     ; false -> return false
 * 1ecb850: str  w21, [x19, #0x40]        ; m_iAdsType = adsType
 *   ...
 * 1ecb908: bl   0x1ecb934   ; new <ShowAds>g__ThisWillBeExecutedOnTheMainThread|17_0
 * 1ecb918: bl   0x1ecb9a0   ; MainThreadManager::Enqueue   <-- ONLY on the true path
 * 1ecb91c: and  w0, w20, #0x1  ; return the (false) result
 * ```
 * `MainThreadManager::Enqueue` at `0x1ECB918` is the **only** thing in `ShowAds` that
 * starts the coroutine N3 lives in, and `tbz` at `0x1ECB84C` branches *over* it. So:
 *
 *  * gate **true**  -> coroutine runs -> N3 swaps `ShowRewarded` for `FinishAds` -> reward.
 *  * gate **false** -> `ShowAds` returns `false` at `0x1ECB91C`, **coroutine never created**,
 *    **N3 never executes**. The caller then takes the failure branch and shows the popup.
 *
 * The failure branch is visible at both Headcup entry points, e.g.
 * `UIMatchOne.<OnJoypadInput>d__221::MoveNext` (`0x1991E44`):
 * ```
 * 1991e44: bl   0x1ecb7e8   ; AdsManager::ShowAds(2)   (ADS_TYPE_ENTRY_HEADCUP)
 * 1991e48: tbz  w0, #0x0, 0x1991e5c  ; false -> popup path
 * 1991e5c: mov  x0, x20 ; mov x1, x21 ; mov x2, xzr
 * 1991e68: bl   0x19503dc   ; UIMatchOne::DeactivatePopup(EaseSprite)
 * 1991e74: bl   0x194ff5c   ; UIMatchOne::PopupOkFailToLoadAds()   <-- the popup
 * ```
 * (identical shape at `0x198E0C0` / `0x198E168` in `<OnTouchEnded>d__220::MoveNext`).
 *
 * On any device where the ad network cannot actually serve an ad — Waydroid, no Play
 * Services, no network, no cached ad — the gate returns **false** and the whole reward path
 * is unreachable. **N3′ + N4 only ever worked if the real SDK happened to report a cached ad.**
 *
 * **N7 fixes this by forcing the gate open, and it is safe *because of* N3.** Once N3 is in
 * place the real show can never happen, so "pushing the game into the show path" is no longer
 * a risk — the show path is where N3 has already replaced the show with `FinishAds`. Forcing
 * the gate true is what makes that path *reachable*. The two edits are a set: N3 alone is dead
 * code, N7 alone is pointless (it would just make the game try and fail to show a real ad).
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * Three edits, all arm64: two 8-byte writes and one 4-byte write.
 *
 * ============================================================================
 * ANCHOR 1 — N3′: replace the coroutine dispatch with a direct FinishAds() call
 * ============================================================================
 * ```
 * RVA 0x1ECB914  file 0x1EC7914  (file = RVA − 0x4000)
 * 32-byte verify window, exactly 1 hit in the 61,223,136 B lib:
 *   e00315aa 22000094 80020012 f44f42a9 f65741a9 fe0743f8 c0035fd6 6ee5e097
 * write 8 B at the window start:
 *   original     e00315aa 22000094   mov x0, x21 ; bl 0x1ECB9A0 (MainThreadManager::Enqueue)
 *   replacement  e00313aa 87000094   mov x0, x19 ; bl 0x1ECBB34 (AdsManager::FinishAds)
 * ```
 * Byte order above is **file** order. `llvm-objdump` prints the word reversed, so the
 * same two instructions read there as `aa1303e0` / `94000087`. Getting this backwards
 * produces a valid-looking but nonsensical instruction — worth stating because it is
 * an easy mistake to repeat.
 *
 * ── Why this site, and what was WRONG before ──
 * The first attempt anchored inside the `ShowAds` coroutine
 * (`<ShowAds>d::MoveNext`, RVA `0x1ECC2F8`), swapping
 * `bl AdsManager::ShowRewarded()` for `bl AdsManager::FinishAds()`. **That crashed the
 * game with SIGSEGV**, and the reason is worth recording because the original KDoc
 * asserted the exact opposite:
 *
 * ```
 * 1ecc2f8: stp  x30, x19, [sp, #-0x10]!
 * 1ecc2fc: ldr  w8, [x0, #0x10]      ; x0 = the STATE MACHINE struct
 * 1ecc300: mov  x19, x0
 * 1ecc304: mov  w0, wzr              ; <<< ZEROES x0
 * 1ecc308: cmp  w8, #0x1
 * 1ecc30c: b.eq  0x1ecc348
 * 1ecc310: cbnz w8, 0x1ecc350
 * 1ecc314: ldr  x8, [x19, #0x20]     ; <<< AdsManager lands in x8, NOT x0
 * 1ecc318: mov  w9, #-0x1
 * 1ecc31c: str  w9, [x19, #0x10]
 * 1ecc320: cbz  x8, 0x1ecc358
 * 1ecc324: strb wzr, [x8, #0x38]
 * 1ecc328: bl   0x1ecbca8            ; the old anchor
 * ```
 *
 * The old KDoc claimed *"x0 already holds the AdsManager at the call site"*. **It does
 * not.** `mov w0, wzr` at `0x1ECC304` zeroes `x0`, and the AdsManager is loaded into
 * **`x8`** at `0x1ECC314`. The bug survived static review because the original callee
 * *ignores* `x0` completely:
 *
 * ```
 * AdsManager::ShowRewarded()  0x1ECBCA8
 *   1ecbca8: sub  sp, sp, #0x70
 *   1ecbcb4: adrp x19, 0x3a71000     ; prologue never reads incoming x0
 *   1ecbcdc: movi v0.2d, #0
 *   1ecbce4: mov  x0, xzr            ; <<< overwrites x0 itself
 * ```
 *
 * so passing `x0 = 0` to it is harmless. `FinishAds` is the opposite — it *requires*
 * `this` in `x0`:
 *
 * ```
 * AdsManager::FinishAds()  0x1ECBB34
 *   1ecbb50: mov  x19, x0            ; this
 *   ...
 *   1ecbb98: ldr  x1, [x19, #0x20]   ; <<< SIGSEGV with this == null
 *   1ecbb9c: mov  x2, xzr
 * ```
 *
 * Device confirmation — repeated `Forwarding signal 11` with the faulting PC inside
 * `FinishAds` at the `ldr x1, [x19, #0x20]` / `_lastShowTime` read, i.e. `this == null`.
 * Because N7 forces the gate open, N3′'s predecessor ran for the **first time** on the
 * device and failed immediately. Any anchor in that coroutine must move `x8` into `x0`,
 * and the only two consecutive free instruction slots before the coroutine's GC write
 * barrier are already consumed by `<>4__this` bookkeeping — see the rejected note below.
 *
 * ── The fix: do it in ShowAds, where `this` is already in a callee-saved register ──
 * ```
 * AdsManager::ShowAds(AdsType)  0x1ECB7E8
 *   1ecb808: mov  x19, x0            ; x19 = this, callee-saved
 *   ...
 *   1ecb8f8: mov  x0, x19
 *   1ecb8fc: ldr  x8, [x8]
 *   1ecb900: ldr  x8, [x8, #0xb8]    ; MainThreadManager static fields
 *   1ecb904: ldr  x21, [x8]          ; x21 = MainThreadManager.instance
 *   1ecb908: bl   0x1ecb934          ; new <ShowAds>…|17_0  (state-machine ctor)
 *   1ecb90c: cbz  x21, 0x1ecb930     ; throw if no main-thread manager
 *   1ecb910: mov  x1, x0             ; x1 = the state machine
 *   1ecb914: mov  x0, x21            ; <-- REPLACED: mov x0, x19  (this)
 *   1ecb918: bl   0x1ecb9a0          ; <-- REPLACED: bl 0x1ecbb34 (FinishAds)
 *   1ecb91c: and  w0, w20, #0x1      ; return the (N7-forced) availability result
 *   1ecb920: ldp  x20, x19, [sp], #0x30 … ret
 * ```
 * 1. **`x0` is set explicitly from `x19`**, so `FinishAds` gets a valid `this`. No register
 *    is left to chance and no 32-bit truncation is involved (the tempting
 *    `FinishAds`-entry variant `mov x19, x0` → `mov x19, x8` is unsafe: `ldrb w8` at
 *    `0x1ECBB48` zeroes bits 32-63 of `x8` before the read).
 * 2. **The coroutine is never scheduled, so `ShowRewarded()` is never reached** — the ad
 *    cannot show even in principle. This is stronger than the old anchor, which still
 *    *ran* the coroutine and merely swapped the call inside it. The state-machine object
 *    built at `0x1ECB908` is simply discarded (one small short-lived allocation per
 *    attempt; unreachable code, not a leak).
 * 3. **No GC write-barrier games.** The alternative in-coroutine fix would have had to
 *    rewrite `str xzr, [x0, #0x18]!` (`0x1ECC330`) and the `bl` write barrier
 *    (`0x1ECC338`) as well, because `FinishAds` clobbers `x0` and the barrier
 *    (`b 0x1706DE0`) expects the just-stored value. Here the store, the barrier and the
 *    coroutine epilogue are all left untouched.
 * 4. **Clean return value.** `ShowAds` still returns `w20 & 1`, which N7 forces to `1`,
 *    so all 8 call sites see success and none take the
 *    `PopupOkFailToLoadAds` branch at `0x198E0C4` / `0x1991E48`.
 * 5. `x19` is callee-saved, so it survives the `FinishAds` call and the epilogue's
 *    `ldp x20, x19, [sp], #0x30` / `ret` is untouched.
 *
 * Both replacement encodings were written into a copy of the real lib and re-disassembled
 * with `llvm-objdump`, which prints `mov x0, x19` then `bl 0x1ecbb34`.
 *
 * ── Anchor 2 — N4: remove the 10-second cooldown ──
 * ```
 * RVA 0x1ECBBD4  file 0x1EC7BD4  (file = RVA − 0x4000)
 * 32-byte verify window, exactly 1 hit in the 61,223,136 B lib:
 *   a4020054 741200f9 34dd00d0 88765439 c8000035 80c600d0 00ec41f9 2be4e097
 * write 4 B at the window start:
 *   original     a4020054   b.mi 0x1ECBC28   ; "if elapsed < 10s, return"
 *   replacement  1f2003d5   nop
 * ```
 * ```
 * 1ecbbcc: fmov d1, #10.00000000
 * 1ecbbd0: fcmp d0, d1
 * 1ecbbd4: b.mi 0x1ecbc28        <-- REPLACED
 * 1ecbbd8: str  x20, [x19, #0x20] ; _lastShowTime = DateTime.Now
 * ```
 * `FinishAds()` early-outs if less than 10 s have elapsed since the last ad
 * (`DateTime.Now − this->_lastShowTime` → `TimeSpan.TotalSeconds` → `fcmp 10.0`).
 *
 * **N4 is only meaningful together with N3.** Without N3 the ad really does show and the
 * cooldown is legitimate, so removing it alone would just let the player spam real ads.
 * With N3 the show never happens, so the cooldown is pure friction — back-to-back taps on
 * any of the 8 buttons would otherwise pay out only once per 10 s.
 *
 * Harmless side effect: after the `nop`, `_lastShowTime` is refreshed even when the
 * cooldown would have blocked. That field (`AdsManager` +`0x20`) is read **nowhere else**
 * in the game, so nothing else can observe the change.
 *
 * The replacement was re-disassembled: `nop`, with `str x20, [x19, #0x20]`
 * (`_lastShowTime = now`) correctly following.
 *
 * ── Anchor 3 — N7: force the availability gate open (this is what makes N3 reachable) ──
 * ```
 * RVA 0x17B22B8  file 0x17AE2B8  (file = RVA − 0x4000)
 * 32-byte verify window, exactly 1 hit in the 61,223,136 B lib:
 *   fe0f1ef8 f44f01a9 f4150190 88466139 f30300aa c8000037 e0fd0090 004443f9
 * write 8 B at the window start:
 *   original     fe0f1ef8 f44f01a9  str x30,[sp,#-0x20]! ; stp x20,x19,[sp,#0x10]
 *   replacement  20008052 c0035fd6  mov w0, #0x1 ; ret
 * ```
 * Original function entry, and what the two 8 written bytes turn it into:
 * ```
 * before:  17b22b8: str  x30, [sp, #-0x20]!   <-- REPLACED
 *          17b22bc: stp  x20, x19, [sp, #0x10]  <-- REPLACED
 *          17b22c0: adrp x20, 0x3a6e000
 *          17b22c4: ldrb w8, [x20, #0x851]      (the real availability flag read)
 *          ...
 * after:   17b22b8: mov  w0, #0x1            <-- REPLACED
 *          17b22bc: ret                       <-- REPLACED
 *          17b22c0: adrp x20, 0x3a6e000      (now unreachable — dead tail, harmless)
 * ```
 * The whole body after the entry stub becomes unreachable dead code; nothing else in the
 * function needs to be touched because the return value is all its callers read.
 *
 * **This is the edit that fixes the "failed to load advertisement" bug.** `ShowAds` gates
 * on this return value (`tbz w0, #0x0` at `0x1ECB84C`) *before* it ever creates the coroutine
 * N3 lives in — see "THE DEVICE BUG" above. N7 makes that gate always pass, the coroutine is
 * created, N3 swaps the show for `FinishAds`, and the reward lands.
 *
 * N7 is **only** safe together with N3, and the dependency runs in *both* directions:
 *  * N3′ without N7 → dead code (the shipped bug: the gate returns false, `ShowAds`
    bails at `0x1ECB91C`, and the coroutine is never created).
 *  * N7 without N3 → the game really does call `showRewardedVideo()`, the SDK fails, and the
 *    player gets the popup *and* no reward — strictly worse than doing nothing.
 * N4 is orthogonal and stays required for the "no waiting between claims" promise: the
 * cooldown lives in `FinishAds`, which N3 now calls on every single tap.
 *
 * **IMPACT ANALYSIS — the full caller inventory, measured not guessed.**
 * `isRewardedVideoAvailable` has exactly **2** call sites in the whole binary, established by
 * indexing **every** `BL`/`B` in the executable LOAD segment (`off 0x15048f4`, `0x20E5A1C` B —
 * 1,299,050 branch sites, 163,533 distinct targets) rather than by `strings`/grep:
 *
 * | caller | enclosing function | impact of forcing true |
 * |---|---|---|
 * | `0x1ECB844` | the gate in `AdsManager::ShowAds` (`0x1ECB7E8`) | **desired** — lets the reward path run |
 * | `0x17B1E58` | `IronSourceDemoScript::OnGUI` (`0x17B1C8C`), the SDK's own bundled demo MonoBehaviour | **harmless** — never runs; see below |
 *
 * The second site is the SDK demo's "Show Rewarded Video" button handler, and it is worth
 * being precise about why it is dead rather than hand-waving:
 * ```
 * 17b1e50: bl   0x17ae3ec   ; IronSource.get_Agent()
 * 17b1e54: cbz  x0, ...
 * 17b1e58: bl   0x17b22b8   ; isRewardedVideoAvailable()   <-- N7 forces this true
 * 17b1e5c: tbz  w0, #0x0, 0x17b1e7c
 * 17b1e70: bl   0x17ae3ec   ; get_Agent()
 * 17b1e78: bl   0x17b235c   ; IronSource.showRewardedVideo()  <-- only on the true path
 * ```
 * So *if* it ran, N7 would let a real ad show. It does not run, on two independent pieces of
 * evidence:
 *  1. `IronSourceDemoScript` appears **exactly once** in `dump.cs` — the class declaration
 *     itself. No managed code news it up, holds a field of it, or references it by any name,
 *     and `OnGUI` is dispatched by Unity off the scripting message table, so it needs the type
 *     to be on an active GameObject. Nothing ever puts it on one.
 *  2. The 439,983,082 B OBB contains no `IronSourceDemoScript` string at all — only
 *     `IronSourceMediatedNetworkSettings`. There is no demo scene in the shipped content.
 * (`IronSource.validateIntegration` is a *different* function, RVA `0x17AE49C`, and is not a
 * caller of this one — do not conflate the two if this is ever re-derived.)
 *
 * Supporting counts from the same index, for the record:
 *  * `AdsManager::ShowAds` (`0x1ECB7E8`) — exactly **8** callers: `0x18BA590`, `0x192C114`,
 *    `0x198DE14`, `0x198E0C0`, `0x1990B4C`, `0x1991E44`, `0x19BB040`, `0x19BB268` — matching
 *    the 8 `AdsType` entry points.
 *  * `UIMatchOne::PopupOkFailToLoadAds` (`0x194FF5C`) — exactly **2** callers: `0x198E168`
 *    and `0x1991E74`. Only the two Headcup-entry paths surface the popup; the other six fail
 *    silently, which is why the bug looked inconsistent.
 *  * `AdsManager::ShowRewarded` (`0x1ECBCA8`) — exactly **1** caller, `0x1ECC328`, inside
 *    the `ShowAds` coroutine. N3′ removes the `Enqueue` that schedules that coroutine, so
 *    this call site becomes **unreachable** and the ad cannot be shown at all.
 *  * `AdsManager::FinishAds` (`0x1ECBB34`) — exactly **1** pre-existing caller, `0x1ECBD64`
 *    (`RewardedVideoAdRewardedEvent`, the real ad's reward callback). N3 adds a second. It is
 *    safe to call directly: no arguments beyond `this`, which N3 already supplies.
 *
 * The replacement was written into a copy of the real lib and re-disassembled with
 * `llvm-objdump`: it prints `mov w0, #0x1` then `ret`, confirming the encoding.
 *
 * ============================================================================
 * WHY 32-BYTE WINDOWS, AND WHAT THE ACTUAL HIT COUNTS ARE
 * ============================================================================
* `../shared/NativeByteEditor.kt` mandates a 32-byte window with a whole-file
 * `count == 1` check, and all three of the windows above measure **exactly 1 hit** in the
 * 61,223,136 B arm64 lib. That engine resolves and validates *every* anchor before
 * writing a single byte, so a moved or changed app build fails loudly with nothing
 * written rather than half-patching the lib.
*
 * Re-measured on the real shipped lib (`analysis/head-basketball/apk`):
 * ```
 * anchor   4 B window   8 B window   32 B window
 * N3′      2 hits      2 hits       1 hit   <-- enforced (re-measured, see note)
 * N4        9 hits      1 hit        1 hit   <-- enforced
 * N7    24,078 hits  24,078 hits    1 hit   <-- enforced
 *
 * the standard IL2CPP prologue, for contrast:
 *   `str x30,[sp,#-0x20]!` alone  ( 4 B) -> 24,078 hits
 *   + `stp x20,x19,[sp,#0x10]`   ( 8 B) -> 24,078 hits
 * ```
 * The **24,078** figure is the prologue count, and **N7 sits exactly on it**: N7 targets
 * `IronSource.isRewardedVideoAvailable`, a plain SDK wrapper whose entry is the textbook
 * IL2CPP prologue, so its own 4- and 8-byte windows are *literally* the most ambiguous
 * pattern in the binary. Its intermediate widths measure 10 hits at 12 B and 1 hit at 16 B,
 * but neighbouring IronSource wrappers share the same 12-byte shape — e.g.
 * `showRewardedVideo` (`0x17B235C`) and `showInterstitial` (`0x17B2690`) both start
 * `fe0f1ef8f44f01a9f4150190…` — so 32 B is what actually disambiguates. N3 and N4 happen to
 * be less ambiguous than a typical function prologue (their 4-byte windows still hit 14 and
 * 9 times, because `bl` and `b.mi` encodings repeat). **No** site here is unique at 4 bytes, so
 * a blind search would corrupt an arbitrary function, which is exactly what the 32-byte
 * `count == 1` rule exists to prevent. This patch writes 8, 8 and 4 bytes over three
 * 32-byte-verified windows — replacement length is an **independent axis** from verify
 * length, so the uniqueness proof covers every byte that can be touched.
 *
 * ============================================================================
 * REJECTED ALTERNATIVES — do not re-litigate these
 * ============================================================================
 * * **N5 — native `IronSource.showRewardedVideo()` → `ret`** at RVA `0x17B235C`
 *   (`fe0f1ef8` → `c0035fd6`). **This one deletes the reward**: the Java listener never
 *   fires, so `AdsManager.RewardedVideoAdRewardedEvent` never runs and `FinishAds` is never
 *   reached. Explicitly marked do-not-use-alone. Worth recording why the window length
 *   matters here: `showRewardedVideo` and `showInterstitial` (`0x17B2690`) have an
 *   **identical 16-byte prologue window**, so only a 32-byte window tells them apart.
* * ~~**N7 — forcing `isRewardedVideoAvailable()` → `true`** (`0x17B22B8`).~~ **REJECTION
*   WITHDRAWN — N7 IS NOW SHIPPED AS ANCHOR 3 AND IS REQUIRED. Do not "restore" the old
*   reasoning.** It previously read: *"Self-defeating: it pushes the game **into** the show
*   path instead of out of it. The patch-patterns catalog's rule 31 applies directly —
*   'forcing an availability gate is self-defeating, because the gate and the cooldown are
*   the same decision'. It must also not be combined with N4."* All three clauses are wrong
*   for this patch, and the device proved it:
*    1. **The rule-31 premise does not hold here.** Rule 31 is about keeping the *real ad
*       show* and bypassing the gate that guards it — there, "available" and "show it now"
*       genuinely are the same decision, so forcing the gate true just spams real ads. This
*       patch does the exact opposite: **N3 already guarantees the real show is never called**,
*       and the code it reaches (`FinishAds`) is the game's own reward dispatcher, not an ad.
*       With the show already suppressed, "pushing the game into the show path" is not a risk —
*       it is the only way to reach the reward at all.
*    2. **The claim that N7 "must not be combined with N4" was simply incorrect** — they act
*       on different functions. N4 nops the cooldown branch inside `FinishAds` (`0x1EC7BD4`),
*       N7 replaces the entry of an IronSource SDK wrapper (`0x17AE2B8`). They cannot interact.
*       And since N3 makes the coroutine run on *every* tap, N4 is what keeps the promise of
*       no waiting between claims.
*    3. **The claim that N7 alone is unnecessary was the shipped bug.** N3 + N4 shipped and
*       produced *"failed to load advertisement"* every time, because the gate at
*       `0x1ECB844` returned false, `ShowAds` bailed at `0x1ECB91C`, and N3's coroutine was
*       never created. See "THE DEVICE BUG" above.
* * **N8 / R2 — `AdsManager.ShowAds(AdsType)` → `return false`** (`0x1ECB7E8`, write
*   `00008052 c0035fd6`). `ShowAds` returns `bool`, and **4 of the 8 call sites consume it**
*   (`tbz w0,#0` at `0x198E0C4` and `0x1991E48`), so forcing `false` skips the reward too
*   and deletes the free Death-mode / Headcup entries. (And never a bare `ret` there — that
*   would return `this` ≠ 0, i.e. "ad shown".) **N3 + N4 + N7 is strictly better.**
 *
 * ============================================================================
 * DELIBERATELY EXCLUDED — the Java-side guarantee (S2)
 * ============================================================================
 * The proper belt-and-braces "an ad can never appear" fix is a `bytecodePatch` NOP-ing
 * `com/ironsource/unity/androidbridge/AndroidBridge.showRewardedVideo()` — the real smali
 * choke point (`.registers 1`, a single `invoke-static` at index 0; the recon/brief
 * assumption that the C# talks to `com.unity3d.services.store`-style ads is wrong — the
 * IL2CPP metadata contains **zero** `com.unity3d.services.ads` / `UnityAds` / `BannerView`
 * literals and reaches ironSource exclusively through `AndroidBridge`).
 *
 * It is **not** included here, for two reasons:
 *  1. it has the same "deletes the reward" caveat as N5, so it must ship *with* N3 — and
 *     N3 already prevents the call from ever happening, so it would be pure redundancy;
 *  2. it would force this entry to become a `bytecodePatch` with a `dependsOn(...)` on a
 *     companion `rawResourcePatch`, i.e. two entries (or an `internal object` builder)
 *     instead of the single clean one shipped here.
 * It can be added later as its own patch — the same shape applies to `showInterstitial`,
 * `loadRewardedVideo`, `displayBanner` and `init`.
 *
 * ============================================================================
 * armv7 — NOT COVERED
 * ============================================================================
* * **All three anchors are arm64-only.** `dump.cs` RVAs are the arm64 build; the armv7
 * `libil2cpp.so` is a separate compilation (53,216,188 B) with entirely different
 * addresses, and the armv7 ad-path RVAs are **[UNVERIFIED]** in the target survey.
 * Guessing an offset and writing over it would be exactly the corruption this engine
 * exists to prevent, so this patch targets **arm64 only** and leaves the armv7 lib
 * untouched.
 *
 * Consequences, which the user must be told about:
 *  * **N3/N4/N7 have no armv7 arm, so both ABIs must remain in the install.** Do **not** use
 *    `--striplibs`.
 *  * On an armv7 device the "watch ad" flow simply behaves **normally** — the ad still
 *    shows and the 10 s cooldown still applies. Nothing breaks; the patch is a no-op
 *    there.
 *  * This patch alone is arm64-only, but the **overall patch set is not**: N1
 *    (`../currency/UnlimitedPointsPatch.kt`) *does* cover armv7 at `0x119A8D0`.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * Top-level `rawResourcePatch` val, `default = true`, independently selectable — same
 * shape as N1 and N2. The three anchors are mutually dependent (N3 needs N7 to be reachable,
 * N7 needs N3 to be safe, N4 needs N3 to be meaningful) and therefore ship in **one** entry
 * rather than three, so a user cannot enable half of the feature. None has an external
 * partner, so no `dependsOn(...)` is needed.
 *
 * A `rawResourcePatch` anywhere in the graph forces `ResourceMode.RAW_ONLY`, which is
 * what makes the raw-extracted `root/lib/arm64-v8a/libil2cpp.so` exist and resolve
 * via `get("lib/arm64-v8a/libil2cpp.so", true)`. `listApkEntries("lib/")` returns
 * **empty** for this app (DEFLATE-compressed libs — the Crossy Road trap) and must
 * not be used.
 *
 * All three patches touch **disjoint** offsets of the same file (N1 arm64 `0x1C10518`,
 * N1 armv7 `0x119A8D0`, N2 arm64 `0x1E88E30`, N3′ arm64 `0x1EC7914`, N4 arm64
 * `0x1EC7BD4`, N7 arm64 `0x17AE2B8`), so enabling any combination is safe and
 * order-independent.
 *
 * ============================================================================
 * DO NOT
 * ============================================================================
 * * **Never bump `versionCode` (471).** The 419 MB OBB is named
 *   `main.471.com.dnddream.HeadBasketball.obb` — the `471` *is* the versionCode.
 *   Unity resolves the expansion as `%s/main.%d.%s.obb`, so a bump makes the OBB
 *   unfindable and the game unplayable on any device holding the official OBB.
 * * **Never touch `assets/unity_obb_guid`** (`8613441d-1d79-44ca-8c7a-2b4bc78f1475`,
 *   36 B, no trailing newline) — `libunity.so` aborts with *"Application OBB has
 *   mismatching GUID"*.
 * * **Never touch `assets/bin/Data/Managed/Metadata/global-metadata.dat`** — it is
 *   inside the APK, and every RVA above means nothing if it does not survive the
 *   round-trip byte-identical.
 * * Keep `READ_EXTERNAL_STORAGE` and `android:installLocation="preferExternal"`; do
 *   not delete `assets/bin/Data/data.unity3d` (750,735 B boot/splash stub).
 * * **Do not remove N7.** It is the edit that makes N3 reachable; without it this patch
 *   ships the "failed to load advertisement" bug again. Do not "restore" the old
 *   rejected-alternatives wording either — see the withdrawn entry above for why the
 *   Dead-Target rule-31 argument does not apply when the real show is already suppressed.
 * * Do **not** add N5. It deletes the reward: the Java listener never fires, so
 *   `RewardedVideoAdRewardedEvent` → `FinishAds` never runs.
 * * Do not stub `AdsManager::FinishAds` at its entry (`0x1ECBB34`) as an alternative to
 *   N4: that would skip the coroutine start (the main-thread dispatch and
 *   `MonoBehaviour.StartCoroutine`), so the reward would never run. N4 nops only the
 *   cooldown branch and is the correct minimal edit.
 * * Do not `--striplibs` either ABI — see the armv7 section above.
 *
 * Full packaging rule set: `../shared/NativeByteEditor.kt`. Measured anchors:
 * analysis/head-basketball/notes/targets.md §3 (ad surface), §4 (N3, N4, N5, N7, N8) and
 * analysis/head-basketball/notes/possible-patches.md §1, §3, §4.
 */
@Suppress("unused")
val freeAdRewardsPatch = rawResourcePatch(
    name = "Free Ad Rewards",
    description = "Get your ad rewards for free. Every \"watch ad\" button gives you the " +
        "reward right away — no ad to sit through, no waiting.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HEAD_BASKETBALL)

    execute {
        applyNativeEdits(
            "Free Ad Rewards",
            listOf(AbiEdits("arm64-v8a", ARM64_ANCHORS)),
        )
    }
}

// arm64 only — file offset = RVA − 0x4000 (exec LOAD off 0x15048f4 → vaddr 0x15088f4).
// armv7 deliberately absent: the AdsManager ad-path RVAs are unverified (see the KDoc),
// and N3 without N4 would be a worse experience than no patch at all — and N3 without N7
// does not work at all (see "THE DEVICE BUG" in the KDoc).
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "ShowAds: mov x0,x21 ; bl Enqueue -> mov x0,x19 ; bl FinishAds() (arm64)",
        rva = 0x1ECB914,
        fileOffset = 0x1EC7914,
        verifyHex = "e00315aa2200009480020012f44f42a9f65741a9fe0743f8c0035fd66ee5e097",
        // mov x0, x19   — x19 is `this` (the AdsManager) in ShowAds, set at 0x1ECB808
        // bl  0x1ecbb34  — AdsManager::FinishAds(), i.e. the reward dispatcher
        // Byte order is FILE order (llvm-objdump prints the word reversed: aa1303e0 / 94000087).
        // One combined 8-byte anchor, not two 4-byte ones: these instructions are 4 bytes apart,
        // so two 32-byte verify windows would overlap and the second would read bytes the first
        // had already rewritten, breaking the resolve-then-write guarantee in NativeByteEditor.
        replacementHex = "e00313aa87000094",
    ),
    NativeAnchor(
        label = "AdsManager.FinishAds(): 10 s cooldown b.mi -> nop (arm64)",
        rva = 0x1ECBBD4,
        fileOffset = 0x1EC7BD4,
        verifyHex = "a4020054741200f934dd00d088765439c800003580c600d000ec41f92be4e097",
        // nop — str x20, [x19, #0x20] (_lastShowTime = now) follows correctly
        replacementHex = "1f2003d5",
    ),
    NativeAnchor(
        label = "IronSource.isRewardedVideoAvailable(): prologue -> mov w0,#1 ; ret (arm64)",
        rva = 0x17B22B8,
        fileOffset = 0x17AE2B8,
        verifyHex = "fe0f1ef8f44f01a9f415019088466139f30300aac8000037e0fd0090004443f9",
        // mov w0, #0x1 ; ret — forces AdsManager::ShowAds past its availability gate at
        // 0x1ECB84C, so the coroutine carrying the N3 edit is actually created. Without this
        // the gate returns false, ShowAds bails at 0x1ECB91C, N3 never runs and the caller
        // shows "failed to load advertisement".
        replacementHex = "20008052c0035fd6",
    ),
)
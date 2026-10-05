package app.headsoccer.patches.ads

import app.headsoccer.patches.shared.Constants.COMPATIBILITY_HEAD_SOCCER
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Head Soccer 7.1.6 · **Free Ad Rewards** — every "watch ad" button pays out instantly.
 *
 * cocos2d-x (`com.dnddream.headsoccer.android`, versionCode 345). **DEX-only — no native edit.**
 * The AppLovin SDK is never called and no ad is ever displayed; the game's own reward dispatcher
 * is invoked directly, exactly the way the real completion callback would have.
 *
 * ============================================================================
 * THIS IS *NOT* AN AD-BLOCK — read this before "simplifying" anything
 * ============================================================================
 * The obvious way to remove ads is to stop them showing. **In this game that deletes content**,
 * and the smali proves it. The entire ad surface is *rewarded* — there is not a single
 * interstitial in the app:
 *
 * ```
 * headsoccer.ShowAds()                    the ONLY myIncent call site in the whole DEX
 *   ├─ isAdReadyToDisplay()               gate
 *   └─ myIncent.show(ctx, $30, $28, $27, $29)   5-arg → AppLovinAdRewardListener is NON-NULL ($29)
 *
 * headsoccer.playRewarded(View)           the sibling free-reward button (smali 6579)
 *   └─ myIncent.show(ctx, null, null, $2) 4-arg, TWO NULL LISTENERS → grants nothing by design
 * ```
 *
 * Suppressing the show without running the game's own completion handler would take away every
 * rewarded-ad payout (free points, free items, free entry). So the whole point of this patch is
 * the difference between
 *
 * **"suppress the show and drop the reward"** (a naive ad-block, which deletes content) and
 * **"skip the show, still run the game's own completion handler"** (what ships here).
 *
 * The game's own handler is `headsoccer$27.adHidden(...)`, whose entire body is
 * `if (m_bRewardOK && m_bVideoCompleted) { mGLView.queueEvent(headsoccer$27$1); }` — and
 * `headsoccer$27$1.run()` is exactly `access$19(...)` → `headsoccer.RewardOK()`, the native reward
 * grant. The patch reproduces precisely that pair of effects and never touches the SDK.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `headsoccer.ShowAds()V` — smali 3943, `.registers 9` — body replaced with:
 *
 * ```
 * m_bRewardOK       = true    # as set by the shipped pre-show block (smali 3977)
 * m_bVideoCompleted = true    # as set by headsoccer$28.videoPlaybackEnded(.., true)
 * m_bShowAdver      = false   # as cleared by headsoccer$27.adHidden on completion
 *
 * SuccessAds(1)                # headsoccer$26's payload — "ad succeeded/started"
 * RewardOK()                   # headsoccer$27$1's payload — the native reward grant
 * ```
 *
 * Order matters and matches the real sequence: the shipped body queues `SuccessAds(1)`
 * (`headsoccer$26`) *before* calling `show(...)`, and the reward arrives later via `adHidden`.
 * Both native calls are made from the **GL thread**, which is where `ShowAds()` already runs
 * (`ShowAdver` → `te.handler.post(headsoccer$25)` → `te.ShowAds()`), so no extra queueing is
 * needed or added — the same reasoning as `HillClimbRewardedVideoPatch.kt`, which calls its
 * native JNI reward methods directly rather than re-queueing synthetic lambdas.
 *
 * **`SuccessAds(1)`, not `SuccessAds(0)`.** This is the trap the notes flag and it is worth being
 * explicit: `SuccessAds(0)` is what `headsoccer$31`/`$32` queue on the *not-ready* and
 * *old-SDK* paths, and native then takes its else branch — sets `this+0x188 = 2` and
 * tail-branches to `AdsButton::Error()` (`0x436128`). `SuccessAds(1)` is the success code
 * `headsoccer$26` queues, and `1` is a hard requirement: it is what puts the native
 * `AdsButton` into the state where `RewardOK()` resolves to an actual grant.
 *
 * `myIncent` is never read, so `AppLovinIncentivizedInterstitial.create`/`preload` in `onCreate`
 * and `initializeSdk` can stay exactly where they are — no NPE is possible, because the patched
 * body cannot reach the SDK at all. (The reverse pairing is the risky one: removing the SDK init
 * *without* this patch would NPE at `myIncent.isAdReadyToDisplay()`. This patch alone is
 * sufficient; treat `onCreate` as an optional, separate privacy change.)
 *
 * ============================================================================
 * WHY THE FLAGS ARE SET EVEN THOUGH NOTHING READS THEM HERE
 * ============================================================================
 * `m_bRewardOK` / `m_bVideoCompleted` are read **only** by `headsoccer$27.adHidden`
 * (`$27.smali:92` and `:98`), and `headsoccer$27` is instantiated only as an argument to
 * `myIncent.show(...)` — which the patched body never calls. So functionally they are dead here.
 * They are still written, deliberately, because:
 * 1. it keeps the observable object state byte-identical to a completed rewarded video, so any
 *    *future* code path that reads them (or any code we have not seen) sees the success case;
 * 2. it makes the patch's intent legible in a smali dump rather than looking like a no-op.
 * `m_bShowAdver = false` is likewise the post-`adHidden` value, and it is what the game's own
 * `onKeyDown` handler reads at `headsoccer.smali:4376` to decide whether to swallow BACK during
 * an ad — with no ad ever shown, `false` is the correct state.
 *
 * ============================================================================
 * REGISTER BUDGET & INJECTION SAFETY
 * ============================================================================
 * `ShowAds()` has `.registers 9` and **no exception table** (verified: zero `.catch` entries in
 * smali 3943-4053). Locals `v0…v7` are free; `p0` is the `headsoccer` instance. The injected block
 * uses `v0` (boolean constant) and `v1` (the `headsoccer` instance) only — inside the 4-bit
 * `35c` range, no `/from16` marshalling needed.
 *
 * With no catches, labeled injection merges intact (the BurritoBison/Missiles caveat documented in
 * `CrossyRoadFreeStorePatch.kt` does not apply). The original AppLovin body remains below the
 * injected `return-void` as unreachable dead code — standard short-circuit shape.
 *
 * ============================================================================
 * CAVEAT — read this before reporting a bug
 * ============================================================================
 * * This grants the reward **without** the ad ever displaying, which is the intended behaviour and
 *   matches Head Basketball's shipped `FreeAdRewardsPatch.kt` (anchor 1, N3′). Do not "fix" it by
 *   stubbing `AppLovinIncentivizedInterstitial.show()` instead — that is Head Basketball's
 *   **rejected N5**: the Java listener never fires, so `RewardedVideoAdRewardedEvent` →
 *   `FinishAds` never runs and the reward is *deleted*. The same trap applies here in a different
 *   shape: the only grant site is the SDK callback, so suppressing the show *without* invoking the
 *   reward removes the payout.
 * * `headsoccer.playRewarded(View)` is deliberately **left untouched.** It passes two null
 *   listeners, so it never grants anything even unpatched (verified at `headsoccer$2.smali` — its
 *   `adHidden` only calls `preload`). Patching it would be a no-op with a side effect; if its UI
 *   feedback matters, `returnEarly` is the safe change, but it is not needed for the reward.
 * * Ad **cooldown**: unlike Head Basketball there is no `b.mi`-style 10 s guard on this path in
 *   the Java layer, and `AdsButton`'s own pacing is native. If a rapid series of taps yields only
 *   one grant, that is the native cooldown, not this patch — nothing here suppresses it and
 *   nothing here should (see the N4 debate in Head Basketball's KDoc: removing a legitimate
 *   cooldown is a separate, deliberate decision).
 * * Because the reward now arrives without AppLovin ever being consulted, AppLovin's own SDK-side
 *   impression/reward reporting stops (no network calls at all). Expected.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * One `bytecodePatch` val, `default = true`, independently selectable. Pure DEX — no
 * `rawResourcePatch`, no `dependsOn`, no `ResourceMode.RAW_ONLY`; `libgame.so` is never extracted.
 *
 * Disjoint from `../billing/FreeStorePatch.kt`: different methods (`ShowAds` vs `onBuyPoint`),
 * same class, no shared offsets, so both can be enabled and order does not matter.
 *
 * ============================================================================
 * DO NOT
 * ============================================================================
 * * **Never bump `versionCode` (345).** The OBB is `main.342.com.dnddream.headsoccer.android.obb`
 *   and cocos2d-x resolves the expansion as `%s/main.%d.%s.obb`; a bump makes it unfindable and
 *   the game unplayable on any device with the official OBB.
 * * Do **not** remove the `com.applovin.*` manifest activities or the `applovin.sdk.key`
 *   meta-data while this patch is off — `onCreate` still initialises the SDK. With this patch on
 *   they are inert but harmless; leave them so the two patches stay independently selectable.
 * * Do **not** delete the AppLovin init in `onCreate` as part of this patch (see the NPE note
 *   above) — make it a separate patch if you want the network traffic gone.
 * * Do not rewrite `SuccessAds(1)` as `SuccessAds(0)` — native routes 0 to `AdsButton::Error()`.
 * * Do not stub `headsoccer$27.adHidden()` or `headsoccer$27$1.run()` instead of this body — they
 *   are the grant, and stubbing the *entry* to `ShowAds` around them achieves nothing.
 * * Do not touch `libgame.so`. The `AdsButton::SuccessAds` / `AdsButton::RewardOK` /
 *   `AdsButton::InitReward` implementations stay as shipped; we only decide *which* one runs.
 *
 * Measured anchors: `analysis/head-soccer/notes/targets.md` §T-03 and the native ad-surface
 * inventory in `analysis/head-soccer/notes/purchase-flow.md`.
 */
@Suppress("unused")
val headSoccerFreeAdRewardsPatch = bytecodePatch(
    name = "Free Ad Rewards",
    description = "Every \"watch ad\" reward is granted instantly — no ad appears, no \"failed to " +
        "load advertisement\" popup, and nothing to wait for.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HEAD_SOCCER)

    execute {
        ShowAdsFingerprint.method.addInstructions(0, """
            # p0 = this (headsoccer); v0 = boolean const, v1 = scratch for the instance.
            # Reproduce the exact post-completion state of a watched rewarded video.

            # ── 1. Reward-completion flags, as headsoccer$28.videoPlaybackEnded(.., true)
            #       and headsoccer$27.adHidden would leave them. ──
            const/4 v0, 0x1
            iput-boolean v0, p0, Lcom/dnddream/headsoccer/android/headsoccer;->m_bRewardOK:Z
            iput-boolean v0, p0, Lcom/dnddream/headsoccer/android/headsoccer;->m_bVideoCompleted:Z
            const/4 v0, 0x0
            iput-boolean v0, p0, Lcom/dnddream/headsoccer/android/headsoccer;->m_bShowAdver:Z

            # ── 2. SuccessAds(1) — the SUCCESS code headsoccer$26 queues before showing.
            #       Must be 1: native routes 0 (headsoccer$31/$32) to AdsButton::Error(). ──
            #       `private native` INSTANCE method → invoke-direct, matching what the
            #       synthetic bridges access$18 / access$19 themselves emit.
            const/4 v0, 0x1
            invoke-direct {p0, v0}, Lcom/dnddream/headsoccer/android/headsoccer;->SuccessAds(I)V

            # ── 3. RewardOK() — the native grant, exactly what headsoccer$27$1.run()
            #       invokes (access$19) once adHidden sees both flags set. ──
            invoke-direct {p0}, Lcom/dnddream/headsoccer/android/headsoccer;->RewardOK()V

            return-void
            nop
        """.trimIndent())
    }
}
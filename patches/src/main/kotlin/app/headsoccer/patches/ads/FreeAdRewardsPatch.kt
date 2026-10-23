package app.headsoccer.patches.ads

import app.headsoccer.patches.shared.Constants.COMPATIBILITY_HEAD_SOCCER
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Head Soccer 7.1.8 · **Free Ad Rewards** — every "watch ad" button pays out instantly.
 *
 * cocos2d-x (`com.dnddream.headsoccer.android`, versionCode 352). **DEX-only — no native edit.**
 * 7.1.8 replaced AppLovin with **Google AdMob** (`RewardedAd`); the AdMob SDK is never called and
 * no ad is ever displayed. The game's own reward dispatcher is invoked directly, exactly the way
 * the real completion callback would have.
 *
 * ============================================================================
 * THIS IS *NOT* AN AD-BLOCK — read this before "simplifying" anything
 * ============================================================================
 * The obvious way to remove ads is to stop them showing. **In this game that deletes content**,
 * and the smali proves it. The entire ad surface is *rewarded* — there is not a single
 * interstitial in the app:
 *
 * ```
 * headsoccer.ShowAds()                    the ONLY mRewardedAd.show(...) call site in the whole DEX
 *   ├─ mRewardedAd == null                no-ad path
 *   │    └─ queue $29 → SuccessAds(0)     native AdsButton::Error()
 *   └─ mRewardedAd != null                ad-ready path
 *        ├─ queue $26 → SuccessAds(1)     success code
 *        ├─ setFullScreenContentCallback($27)
 *        └─ show(this, $28)               $28 = OnUserEarnedRewardListener
 * ```
 *
 * Suppressing the show without running the game's own completion handler would take away every
 * rewarded-ad payout (free points, free items, free entry). So the whole point of this patch is
 * the difference between
 *
 * **"suppress the show and drop the reward"** (a naive ad-block, which deletes content) and
 * **"skip the show, still run the game's own completion handler"** (what ships here).
 *
 * The grant site is `headsoccer$27.onAdDismissedFullScreenContent()`:
 *
 * ```
 * m_bShowAdver = false;
 * if (m_bRewardOK) mGLView.queueEvent(headsoccer$27$1);   // → RewardOK()   [native]
 * m_bRewardOK = false;  m_bVideoCompleted = false;
 * hideSystemUI();  LoadRewardedAd();
 * ```
 *
 * and `headsoccer$27$1.run()` is exactly `headsoccer.RewardOK()`, the native reward grant.
 * `headsoccer$28.onUserEarnedReward(...)` only sets `m_bRewardOK = true` and
 * `m_bVideoCompleted = true`. The patch reproduces precisely that pair of effects and never
 * touches the AdMob SDK.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `headsoccer.ShowAds()V` — smali 3448, `.registers 4` — body replaced with:
 *
 * ```
 * m_bRewardOK       = true    # as set by headsoccer$28.onUserEarnedReward(...)
 * m_bVideoCompleted = true    # as set by headsoccer$28.onUserEarnedReward(...)
 * m_bShowAdver      = false   # as cleared by headsoccer$27.onAdDismissedFullScreenContent()
 *
 * SuccessAds(1)                # headsoccer$26's payload — "ad succeeded/started"
 * RewardOK()                   # headsoccer$27$1's payload — the native reward grant
 * ```
 *
 * Order matters and matches the real sequence: the shipped body queues `SuccessAds(1)`
 * (`headsoccer$26`) *before* calling `show(...)`, and the reward arrives later on ad dismissal.
 * Both native calls are made from the **UI thread** (`ShowAdver` → `te.handler.post(headsoccer$21)`
 * → `te.ShowAds()`), which is where `ShowAds()` already runs, so no extra queueing is needed or
 * added — the same reasoning as `HillClimbRewardedVideoPatch.kt`, which calls its native JNI
 * reward methods directly rather than re-queueing synthetic lambdas.
 *
 * **`SuccessAds(1)`, not `SuccessAds(0)`.** This is the trap and it is worth being explicit:
 * `SuccessAds(0)` is what `headsoccer$29` queues on the *no-ad-loaded* path, and native then takes
 * its else branch (`AdsButton::Error()`). `SuccessAds(1)` is the success code `headsoccer$26`
 * queues, and `1` is a hard requirement: it is what puts the native `AdsButton` into the state
 * where `RewardOK()` resolves to an actual grant.
 *
 * `mRewardedAd` is never read by the patched body, so `MobileAds.initialize` / `LoadRewardedAd`
 * can stay exactly where they are — no NPE is possible, because the patched body cannot reach the
 * SDK at all. (The reverse pairing is the risky one: removing the SDK init *without* this patch
 * would break `RewardedAd.load` and the reward. This patch alone is sufficient; treat the ad-SDK
 * init/consent flow as an optional, separate privacy change.)
 *
 * ============================================================================
 * WHY THE FLAGS ARE SET EVEN THOUGH NOTHING READS THEM HERE
 * ============================================================================
 * `m_bRewardOK` / `m_bVideoCompleted` are read by the AdMob callbacks that the patched body
 * never registers, and `m_bShowAdver` is read by `onKeyDown` (`headsoccer.smali`). The patched
 * body writes all three so the observable object state is byte-identical to a completed rewarded
 * video. They are still written, deliberately, because:
 * 1. it keeps any *future* code path that reads them (or any code we have not seen) on the
 *    success case;
 * 2. it makes the patch's intent legible in a smali dump rather than looking like a no-op.
 * `m_bShowAdver = false` is likewise the post-dismissal value, and it is what the game's own
 * `onKeyDown` handler reads to decide whether to swallow BACK during an ad — with no ad ever
 * shown, `false` is the correct state.
 *
 * ============================================================================
 * REGISTER BUDGET & INJECTION SAFETY
 * ============================================================================
 * `ShowAds()` has `.registers 4` and **no exception table** (verified: zero `.catch` entries in
 * smali 3448-3580). Locals `v0…v2` are free (`p0` is `v3`), so the injected block's `v0` scratch
 * register is comfortably inside the 4-bit `35c` range and no `/from16` marshalling is needed.
 *
 * With no catches, labeled injection merges intact (the BurritoBison/Missiles caveat documented in
 * `CrossyRoadFreeStorePatch.kt` does not apply). The original AdMob body remains below the
 * injected `return-void` as unreachable dead code — standard short-circuit shape.
 *
 * ============================================================================
 * CAVEAT — read this before reporting a bug
 * ============================================================================
 * * This grants the reward **without** the ad ever displaying, which is the intended behaviour and
 *   matches Head Basketball's shipped `FreeAdRewardsPatch.kt` (anchor 1, N3′). Do not "fix" it by
 *   stubbing `RewardedAd.show()` instead — the AdMob callback never fires, so
 *   `onAdDismissedFullScreenContent` → `RewardOK()` never runs and the reward is *deleted*. The
 *   same trap applies here in a different shape: the only grant site is the SDK callback, so
 *   suppressing the show *without* invoking the reward removes the payout.
 * * `headsoccer.playRewarded(View)` does not exist in 7.1.8; the AdMob surface is the single
 *   `ShowAds()` path. There is nothing else to patch for rewards.
 * * Ad **cooldown**: unlike Head Basketball there is no `b.mi`-style 10 s guard on this path in
 *   the Java layer, and `AdsButton`'s own pacing is native. If a rapid series of taps yields only
 *   one grant, that is the native cooldown, not this patch — nothing here suppresses it and
 *   nothing here should (see the N4 debate in Head Basketball's KDoc: removing a legitimate
 *   cooldown is a separate, deliberate decision).
 * * Because the reward now arrives without AdMob ever being consulted, AdMob's own SDK-side
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
 * * **Never bump `versionCode` (352).** cocos2d-x still resolves an expansion OBB by the
 *   `main.<versionCode>.com.dnddream.headsoccer.android.obb` name, so a bump can make the OBB
 *   unfindable on devices holding the official expansion. 7.1.8 bundles its assets in the base
 *   APK and ships no OBB, but the version code is not ours to change.
 * * Do **not** remove the AdMob/UMP manifest meta-data (`com.google.android.gms.ads.APPLICATION_ID`,
 *   `com.google.android.gms.ads.flag.*`, `com.google.android.gms.ads.AD_MANAGER_APP`) while this
 *   patch is off — `MobileAds.initialize` in `StartAdMob()` still uses them. With this patch on
 *   they are inert but harmless; leave them so the two patches stay independently selectable.
 * * Do **not** delete the AdMob init / consent flow (`StartAdsWithConsent`, `StartAdMob`,
 *   `LoadRewardedAd`) as part of this patch — make it a separate patch if you want the network
 *   traffic gone.
 * * Do not rewrite `SuccessAds(1)` as `SuccessAds(0)` — native routes 0 to `AdsButton::Error()`.
 * * Do not stub `RewardedAd.show()` or `headsoccer$27.onAdDismissedFullScreenContent()` instead
 *   of this body — they are the grant, and stubbing the *entry* to `ShowAds` around them
 *   achieves nothing.
 * * Do not touch `libgame.so`. The `AdsButton::SuccessAds` / `AdsButton::RewardOK` /
 *   `AdsButton::InitReward` implementations stay as shipped; we only decide *which* one runs.
 *
 * Re-derived for 7.1.8 against `analysis/head-soccer/` (Java: `headsoccer.java:1193`, smali:
 * `classes1/.../headsoccer.smali:3448`).
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
            # p0 = this (headsoccer); v0 = boolean/scratch. .registers 4 → locals v0-v2.
            # Reproduce the exact post-completion state of a watched rewarded video.

            # ── 1. Reward-completion flags, as headsoccer$28.onUserEarnedReward(...)
            #       and headsoccer$27.onAdDismissedFullScreenContent() would leave them. ──
            const/4 v0, 0x1
            iput-boolean v0, p0, Lcom/dnddream/headsoccer/android/headsoccer;->m_bRewardOK:Z
            iput-boolean v0, p0, Lcom/dnddream/headsoccer/android/headsoccer;->m_bVideoCompleted:Z
            const/4 v0, 0x0
            iput-boolean v0, p0, Lcom/dnddream/headsoccer/android/headsoccer;->m_bShowAdver:Z

            # ── 2. SuccessAds(1) — the SUCCESS code headsoccer$26 queues before showing.
            #       Must be 1: native routes 0 (headsoccer$29, no ad loaded) to AdsButton::Error(). ──
            #       `private native` INSTANCE method → invoke-direct, matching what the
            #       synthetic bridges access$1800 / access$1900 themselves emit.
            const/4 v0, 0x1
            invoke-direct {p0, v0}, Lcom/dnddream/headsoccer/android/headsoccer;->SuccessAds(I)V

            # ── 3. RewardOK() — the native grant, exactly what headsoccer$27$1.run()
            #       invokes (access$1900) once onAdDismissedFullScreenContent sees
            #       m_bRewardOK set. ──
            invoke-direct {p0}, Lcom/dnddream/headsoccer/android/headsoccer;->RewardOK()V

            return-void
            nop
        """.trimIndent())
    }
}
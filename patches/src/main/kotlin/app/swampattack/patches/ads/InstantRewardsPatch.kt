package app.swampattack.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.swampattack.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Swamp Attack 4.8.7.0 · **Instant Rewards** — every rewarded video pays out
 * instantly; no ad is ever shown.
 *
 * ### Reward flow (smali-verified, fixedcheck tree)
 *
 * ```
 * native game (Marmalade/SDL)
 *   → NativeInterface.showRewardedAd(placement)            NativeInterface.smali:2398
 *     → AdManager.showRewarded(activity, placement)        AdManager.smali:1644 (UI thread)
 *       → RewardedAdController.show(activity, placement)   RewardedAdController.smali:1260
 *         → MeticaSdk.getAds().showRewarded(...)           (Medica bridges AppLovin MAX et al.)
 *           callbacks → onAdShown / onAdRewarded (rewardEarned=true)
 *                     → onAdHidden → libO7.native_libO7_OnAdFinished(rewardEarned, json)
 *                                   └→ native credits the reward + unblocks the game flow
 * ```
 *
 * Availability: native polls `NativeInterface.isRewardedAdReady()` →
 * `AdManager.isRewardedReady()` → `RewardedAdController.isAdReady()`, and the
 * ready signal `native_libO7_RewardedVideoAdReady()` is fired from
 * `RewardedAdController.load()` via `listener.onAdLoaded`.
 *
 * ### Design — callback injection, hang-proof by construction
 *
 * `native_libO7_OnAdFinished(ZLjava/lang/String;)V` is the ONE point where the
 * game is told a rewarded ad finished (the native side blocks on it). Vanilla
 * calls it exactly once per show — `true` on success (from the `onAdHidden`
 * lambda), `false` on show-failure. Simply no-op'ing the show would drop that
 * call and HANG every revive/double-coins flow; double-running it would
 * double-credit. So the patch replaces `show()`'s body with the exact same
 * success sequence the Metica callbacks would have executed, in the same order:
 *
 * 1. `rewardEarned = true`, `currentPlacement = placement`
 * 2. `listener.onAdShown("applovin")`      — analytics + `setAdPresenting(true)`
 * 3. `listener.onAdRewarded("applovin")`   — mirrors `onAdRewarded$lambda$0` (no-op in this listener)
 * 4. `native_libO7_OnAdFinished(true, "")` — **the** reward credit, called EXACTLY ONCE
 * 5. `listener.onAdHidden("applovin")`     — `setAdPresenting(false)` + `restoreEngineFocus()`
 * 6. `load()`                              — normal post-ad reload keeps the ready-signal cycling
 *
 * Steps 2/3/5 use the identical `invoke-interface` shapes as vanilla
 * (`onAdShowSuccess$lambda$0:874`, `onAdRewarded$lambda$0:776`,
 * `onAdHidden$lambda$0:356`); step 4 is called directly (no try/catch, unlike
 * vanilla's defensive wrapper) — safe because this path is only reachable while
 * the Marmalade game is running, i.e. `liblibO7.so` is loaded and the symbol it
 * declares (libO7.smali:304) necessarily exists; the game itself calls in via
 * JNI. Show runs on the UI thread (AdManager.showRewarded posts it), same thread
 * vanilla's callbacks use (`runOnMain`).
 *
 * The `onAdHidden` shown/hidden pair is balanced: `setAdPresenting(true)` only
 * arms a watchdog and `setAdPresenting(false)` disarms it — and the app itself
 * calls `restoreEngineFocus()` without a prior pause in its own show-failed
 * path, so back-to-back invocation is precedented and safe.
 *
 * Two companion edits keep the offer buttons usable (the #1 pitfall is a gated
 * flow that never appears, or a "Not ready" early-return that also skips
 * `OnAdFinished` — a vanilla hang risk this patch additionally eliminates):
 *
 * * `isAdReady()` → `true`: native's readiness poll always says yes; `show()`
 *   no longer contains the "Not ready" branch at all.
 * * `load()`: the `MeticaAds.isRewardedReady` check result is forced to `true`,
 *   so every `load()` (startup, after each instant show, connectivity callbacks)
 *   takes the "already ready" branch and fires `listener.onAdLoaded` →
 *   `native_libO7_RewardedVideoAdReady()` — the native game is told "rewarded
 *   ad available" immediately, with no real ad ever required.
 *
 * No Metica/mediation SDK is touched; no double credit is possible (SDK show
 * never starts, so its callbacks never fire, and the injected path calls
 * `OnAdFinished` once). Disjoint from [swampAttackRemoveAdsPatch] (different
 * methods of the same controllers — safe together; that patch intentionally
 * leaves rewarded ads working, this one makes them instant).
 *
 * Anchors: analysis/swampattack/notes/rewarded-ads.md.
 */
@Suppress("unused")
val swampAttackInstantRewardsPatch = bytecodePatch(
    name = "Instant Rewards",
    description = "Rewarded videos give their reward instantly — no ad ever " +
        "plays. Free revive, double coins, free items, etc. all work without " +
        "watching anything.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK)

    execute {
        // 1. Rewarded "available" gate always open — the game keeps offering
        //    watch-ad buttons and vanilla's "Not ready" dead-end disappears.
        RewardedAdIsReadyFingerprint.method.returnEarly(true)

        // 2. load(): force the "already ready" branch so listener.onAdLoaded →
        //    native_libO7_RewardedVideoAdReady() fires without a real ad.
        //    instructionMatches[1] = MeticaAds.isRewardedReady invoke (filter
        //    order: Companion.isInitialized, MeticaAds.isRewardedReady,
        //    MeticaAds.loadRewarded); the following move-result writes v0
        //    (smali:980+ method, register allocation version-pinned) — replace
        //    it with a literal `true`, discarding the SDK query result.
        val loadMethod = RewardedAdLoadFingerprint.method
        val moveResultIndex = RewardedAdLoadFingerprint.instructionMatches[1].index + 1
        val resultRegister = loadMethod.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA
        loadMethod.replaceInstruction(moveResultIndex, "const/4 v$resultRegister, 0x1")

        // 3. show(): skip the SDK entirely; replay the exact success callback
        //    sequence (shown → rewarded → OnAdFinished(true) → hidden → load).
        //    Prepended at 0 — the original body becomes dead code. Uses only
        //    v0/v1 (method has .registers 11 with p0-p2 = v8-v10) and the same
        //    invoke shapes vanilla uses in its Metica-callback lambdas.
        RewardedAdShowFingerprint.method.addInstructions(0, """
            const-string v0, "activity"
            invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V
            const-string v0, "placement"
            invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V
            const/4 v0, 0x1
            iput-boolean v0, p0, Lcom/libo7/swampattack/ads/controller/RewardedAdController;->rewardEarned:Z
            iput-object p2, p0, Lcom/libo7/swampattack/ads/controller/RewardedAdController;->currentPlacement:Ljava/lang/String;
            iget-object v0, p0, Lcom/libo7/swampattack/ads/controller/RewardedAdController;->listener:Lcom/libo7/swampattack/ads/RewardedAdListener;
            const-string v1, "applovin"
            invoke-interface {v0, v1}, Lcom/libo7/swampattack/ads/RewardedAdListener;->onAdShown(Ljava/lang/String;)V
            iget-object v0, p0, Lcom/libo7/swampattack/ads/controller/RewardedAdController;->listener:Lcom/libo7/swampattack/ads/RewardedAdListener;
            const-string v1, "applovin"
            invoke-interface {v0, v1}, Lcom/libo7/swampattack/ads/RewardedAdListener;->onAdRewarded(Ljava/lang/String;)V
            const/4 v0, 0x1
            const-string v1, ""
            invoke-static {v0, v1}, Lcom/libo7/swampattack/libO7;->native_libO7_OnAdFinished(ZLjava/lang/String;)V
            iget-object v0, p0, Lcom/libo7/swampattack/ads/controller/RewardedAdController;->listener:Lcom/libo7/swampattack/ads/RewardedAdListener;
            const-string v1, "applovin"
            invoke-interface {v0, v1}, Lcom/libo7/swampattack/ads/RewardedAdListener;->onAdHidden(Ljava/lang/String;)V
            invoke-virtual {p0}, Lcom/libo7/swampattack/ads/controller/RewardedAdController;->load()V
            return-void
        """)
    }
}

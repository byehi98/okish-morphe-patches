package app.vector.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.vector.patches.shared.Constants.COMPATIBILITY_VECTOR
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/**
 * Vector Ad Spoof — grants in-game rewards without showing ads.
 *
 * When the game wants to show a rewarded ad (for coins, energy, boosters),
 * it calls the ad SDK bridge methods. Our patch intercepts these calls and
 * immediately fires the reward callback chain, making the game think an ad
 * was watched and granting the reward.
 *
 * The game is Unity IL2CPP — all game logic (wallet, spending, purchases) lives
 * in libil2cpp.so. The only Java-level surface is the ad SDK callbacks. By
 * spoofing rewarded ad callbacks, the native C# code grants coins/rewards as
 * if a real ad was played. This is the "unlimited money via free ad rewards"
 * approach — coins are earned every time the game offers an ad reward.
 *
 * Affected ad paths:
 *   P1: AdMob Unity plugin (UnityRewardedAd.show)
 *   P2: IronSource LevelPlay bridge (RewardedAd.showAd)
 */
@Suppress("unused")
val vectorAdSpoofPatch = bytecodePatch(
    name = "Vector Ad Spoof",
    description = "Spoofs rewarded ad callbacks to grant coins/rewards without showing ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_VECTOR)

    execute {
        // ═══════════════════════════════════════════════════════════════════
        // P1: AdMob Rewarded Ad Spoof
        // ═══════════════════════════════════════════════════════════════════
        //
        // Original: show() → null-check → runOnUiThread(lambda$show$0)
        //   lambda$show$0 → setImmersiveMode(true) → rewardedAd.show(activity, listener)
        //   → real ad → UnityRewardedAd$4.onUserEarnedReward(RewardItem)
        //   → callback.onUserEarnedReward(type, amount)
        //
        // Patched: show() → null-check → fire callbacks synchronously:
        //   1. fullScreenContentCallback.onAdShowedFullScreenContent()
        //   2. callback.onUserEarnedReward("coins", 1.0f)
        //   3. fullScreenContentCallback.onAdDismissedFullScreenContent()
        //   return-void
        //
        // The native C# proxy receives these callbacks and grants the reward.
        // Method has .registers 3: v0, v1 locals + p0 (this).
        // We use v0 as scratch, v1 to hold the callback interface ref.
        // const/high16 v2, 0x3f800000 borrows p2 (alias for v2 in registers 3)
        // for the float literal — safe because p2 is unused in this parameterless method.

        UnityRewardedAdShowFingerprint.method.addInstructionsWithLabels(0, """
            # Null-check: if rewardedAd is null, fall through to original error log + return
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->rewardedAd:Lcom/google/android/gms/ads/rewarded/RewardedAd;
            if-nez v0, :cond_real

            # ── Fire spoofed reward lifecycle ──────────────────────────
            # 1. onAdShowedFullScreenContent — tells C# the ad started
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->fullScreenContentCallback:Lcom/google/android/gms/ads/FullScreenContentCallback;
            invoke-virtual {v0}, Lcom/google/android/gms/ads/FullScreenContentCallback;->onAdShowedFullScreenContent()V

            # 2. onUserEarnedReward("coins", 1.0f) — grants the in-game reward
            iget-object v1, p0, Lcom/google/unity/ads/UnityRewardedAd;->callback:Lcom/google/unity/ads/UnityRewardedAdCallback;
            const-string v0, "coins"
            const/high16 v2, 0x3f800000    # 1.0f (uses p2 alias — safe, no params)
            invoke-interface {v1, v0, v2}, Lcom/google/unity/ads/UnityRewardedAdCallback;->onUserEarnedReward(Ljava/lang/String;F)V

            # 3. onAdDismissedFullScreenContent — tells C# the ad finished
            iget-object v0, p0, Lcom/google/unity/ads/UnityRewardedAd;->fullScreenContentCallback:Lcom/google/android/gms/ads/FullScreenContentCallback;
            invoke-virtual {v0}, Lcom/google/android/gms/ads/FullScreenContentCallback;->onAdDismissedFullScreenContent()V

            return-void

            :cond_real
            nop
        """.trimIndent())

        // ═══════════════════════════════════════════════════════════════════
        // P2: IronSource LevelPlay Rewarded Ad Spoof
        // ═══════════════════════════════════════════════════════════════════
        //
        // The IUnityRewardedAdListener is a local variable captured in
        // RewardedAd$1 during setupRewardedListener(). It's not stored as a
        // field on RewardedAd, so showAd() can't access it directly.
        //
        // Strategy (same as proven intothedead pattern):
        // 1. Add instance field mUnityRewardedAdListener to RewardedAd
        // 2. Store the listener in setupRewardedListener via iput-object
        // 3. Replace showAd() body to fire callbacks via the stored listener

        val rewardedAdClass = RewardedAdShowAdFingerprint.classDef

        // 1. Add instance field to hold the C# proxy listener.
        // ImmutableField positional args: (definingClass, name, type, accessFlags,
        //   initialValue, annotations, hiddenApiRestrictions).
        rewardedAdClass.fields.add(
            ImmutableField(
                "Lcom/ironsource/unity/androidbridge/RewardedAd;",
                "mUnityRewardedAdListener",
                "Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;",
                AccessFlags.PRIVATE.value,
                null, // initialValue
                null, // annotations
                null  // hiddenApiRestrictions
            ).toMutable()
        )

        // 2. Store the C# proxy when setupRewardedListener is called.
        // p0 = this (RewardedAd), p1 = IUnityRewardedAdListener
        RewardedAdSetupListenerFingerprint.method.addInstructions(0, """
            iput-object p1, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
        """.trimIndent())

        // 3. Fire the reward lifecycle from showAd; fall back to a real ad if unset.
        // .registers 4 = v0, v1 locals + p0 (this), p1 (placement String).
        // v2 aliases p0, v3 aliases p1 — use v2 only AFTER the null-check branch
        // so :cond_real sees intact this/placement for the fallthrough.
        //
        // Callback chain (mirrors RewardedAd$1):
        //   onAdDisplayed("{}") → onAdRewarded("{}", placement, 1) → onAdClosed("{}")
        //
        // The placement string (p1) is passed through to onAdRewarded as the reward name.
        // The game's C# handler matches it against known placement constants (e.g.
        // REWARDED_VIDEO, COIN_REWARD) to determine which reward to grant.
        RewardedAdShowAdFingerprint.method.addInstructionsWithLabels(0, """
            # Null-check: if no stored listener, fall through to real SDK call
            iget-object v0, p0, Lcom/ironsource/unity/androidbridge/RewardedAd;->mUnityRewardedAdListener:Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;
            if-eqz v0, :cond_real

            # Ensure placement is non-null (C# expects a string)
            if-nez p1, :cond_ok
            const-string p1, "REWARDED_VIDEO"
            :cond_ok
            nop

            # ── Fire spoofed reward lifecycle ──────────────────────────
            const-string v1, "{}"

            # 1. onAdDisplayed("{}") — tells C# the ad started
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdDisplayed(Ljava/lang/String;)V

            # 2. onAdRewarded("{}", placement, 1) — grants the in-game reward
            const/4 v2, 0x1
            invoke-interface {v0, v1, p1, v2}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdRewarded(Ljava/lang/String;Ljava/lang/String;I)V

            # 3. onAdClosed("{}") — tells C# the ad finished
            invoke-interface {v0, v1}, Lcom/ironsource/unity/androidbridge/IUnityRewardedAdListener;->onAdClosed(Ljava/lang/String;)V

            return-void

            :cond_real
            nop
        """.trimIndent())
    }
}

package app.pvzfree.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.pvzfree.patches.shared.Constants.COMPATIBILITY_PVZFREE

/**
 * PvZ FREE — Ad Removal & Free Rewards
 *
 * Pure bytecode patch against the GluAds ironSource managers in classes5.dex
 * (`csdk.gluads`, unobfuscated). No resource edits are made, so the remote-config
 * refresh (`ConfigFile.json` / `AdsConfig.json` / Firebase Remote Config) cannot revert
 * it, unlike the documented kill-switch approach.
 *
 * Why this works
 * --------------
 * ironSource/LevelPlay itself is initialised normally — we never break SDK setup or the
 * JNI bridge. Instead we neutralise the four lifecycle methods the native layer drives
 * through `csdk.gluads.GluAdsNativeBridge`:
 *
 *  - `load`      → reports `loadFinished` asynchronously and returns. No network fetch,
 *                  no adapter waterfall, and the native pacing code sees a successful load.
 *  - `isLoaded`  → forced `true`, so the native "watch ad" / interstitial buttons are
 *                  always enabled regardless of frequency caps or cached inventory.
 *  - `show`      → short-circuited. An interstitial reports `showStarted` +
 *                  `showFinished`; a rewarded video reports `showStarted`, injects the
 *                  reward, then reports `showFinished`. No ad is ever displayed or fetched.
 *
 * Events must NOT be emitted synchronously
 * ----------------------------------------
 * The events are delivered from `AdsHelper` (extensions/pvzfree) *after* the patched
 * method has returned, posted to the main looper. Emitting them inline re-entered the
 * native ad state machine while `MarketingComponent::ShowAd` / `DraperIronSourceNativeAdManager::ShowAd`
 * were still on the stack, so the native caller re-set its "ad in progress" state after
 * the close callback had already run. The game then froze on the first frame of the level
 * intro (reproduced on both a phone and Waydroid). Posting the events restores the real
 * SDK's asynchronous callback ordering.
 *
 * The reward grant path is deliberately the *real* one:
 *
 *   EAIronSourceRVManager.sendReward(item, amount, placement)   [private, same class]
 *     → new Reward(uuid, placement, "ironSource", adType(), item, amount)
 *     → listener().onRewardReceived(reward)                     [GluAdsNativeBridge JNI]
 *     → Starlight::AdvertisingListener::onRewardReceived(Reward const&)
 *     → Starlight::AdsModule::OnVideoAdRewarded
 *     → RewardedVideoManager::OnRewardedVideoAdRewarded(placement, amount)
 *
 * The reward payload comes from the live configuration via
 * `getReward(adType, placement)` (the remote reward map), so the exact item/amount the
 * game expects is preserved. If the placement has no configured reward, we fall back to
 * a single `"coins"` so `OnVideoAdRewarded` still fires and the pending
 * `VideoRewardType` — which the native manager already captured on the show request —
 * is granted.
 *
 * `sendReward` is private and `sendPlacementEvent`/`listener`/`adType` are protected in
 * `EAIronSourceManagerBase`, so the extension resolves them reflectively (walking up the
 * class hierarchy) rather than through injected bytecode.
 *
 * Register safety: no method's `.registers` is increased. The RV `show` uses
 * `.registers 16` (p0=v13, p1=v14, p2=v15), the interstitial `show` uses `.registers 6`
 * (p0=v3, p1=v4, p2=v5), both `load` methods reserve v0–v1 above their parameters
 * (p0/p2 are the last registers), and both `isLoaded` methods have v0 free (p0=v1). Every
 * block is prepended at index 0, so no existing instruction or branch target moves.
 */
@Suppress("unused")
val pvzFreeAdsPatch = bytecodePatch(
    name = "PvZ FREE Ad Removal & Free Rewards",
    description = "Removes interstitials and grants rewarded-video rewards instantly without showing an ad.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PVZFREE)

    extendWith("extensions/pvzfree.mpe")

    execute {
        // 1) Rewarded video — show: report the impression, grant the configured reward,
        //    close the impression, then return before rewardedAd.showAd(...).
        //    p1 = adType, p2 = placement (still intact at index 0).
        RvShowFingerprint.method.addInstructions(0, """
            invoke-static {p0, p1, p2}, Lapp/morphe/extension/pvzfree/AdsHelper;->fakeRewarded(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // 2) Rewarded video — load: report success without touching the network.
        RvLoadFingerprint.method.addInstructions(0, """
            invoke-static {p0, p2}, Lapp/morphe/extension/pvzfree/AdsHelper;->fakeLoad(Ljava/lang/Object;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // 3) Rewarded video — isLoaded: keep reporting "loaded" so the game's
        //    RewardedVideoManager passes its pre-check and calls show(), where the
        //    reward is granted. (Marking this false makes the UI fail with
        //    "Something seems to have gone wrong. Try again later.")
        RvIsLoadedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // 4) Interstitial — show: report the lifecycle events and never call showAd.
        InterstitialShowFingerprint.method.addInstructions(0, """
            invoke-static {p0, p2}, Lapp/morphe/extension/pvzfree/AdsHelper;->fakeInterstitial(Ljava/lang/Object;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // 5) Interstitial — load: report success without touching the network.
        InterstitialLoadFingerprint.method.addInstructions(0, """
            invoke-static {p0, p2}, Lapp/morphe/extension/pvzfree/AdsHelper;->fakeLoad(Ljava/lang/Object;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // 6) Interstitial — isLoaded: report "no ad loaded" so the native
        //    MarketingComponent takes its no-fill path and never calls show().
        //    This is what keeps the level intro from freezing on the broken
        //    (never-closing) Unity Ads WebView on this device.
        InterstitialIsLoadedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())
    }
}

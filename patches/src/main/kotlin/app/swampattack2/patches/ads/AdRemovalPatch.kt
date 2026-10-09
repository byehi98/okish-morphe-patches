package app.swampattack2.patches.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.swampattack2.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK_2

/**
 * Swamp Attack 2 v1.3.9 · **Remove Ads** — forced ads can never reach the screen.
 *
 * Cuts every display surface at the Metica ↔ Unity ad-SDK bridge
 * (`Lcom/metica/unity_bridge/UnityBridge;`, smali/classes9). Each target below is
 * verified against the shipped smali and turned into an immediate `return-void`:
 *
 * * **Interstitials** — `showInterstitial(String,String,String,MetaAdsShowCallback)`
 *   never reaches the SDK. (Belt-and-braces: the native engine's
 *   `AreInterstitialsDisabled` hook already stops the C# funnel —
 *   `InterstitialAdManager.TryShowInterstitial` — before any Java is called; this
 *   layer keeps interstitials out even if the native engine is disabled.)
 * * **Banners** — `createBannerWithPosition/Coords` never create, load or attach a
 *   view (UnityBanners.createAdView → retrieveOrCreateAdView + load() + attach
 *   lambda — create* alone can put a banner on screen, so show/load/create are all
 *   blocked), and `showBanner`/`loadBanner` are dead no-ops on top.
 * * **MRECs** — same treatment: `createMrecWithPosition/Coords`, `showMrec`,
 *   `loadMrec`.
 *
 * With no view ever created, every other UnityBanners entry point (hide/destroy/
 * auto-refresh/reposition/setWidth) is an unreachable no-op — nothing is attached
 * to the activity, so no banner or MREC can render. Works regardless of the
 * Firebase/Metica remote config: the block is at the SDK boundary, below any
 * server-driven gating.
 *
 * **Rewarded is deliberately NOT touched here.** Reward videos are completed
 * natively by the bundled engine: the `AreVideoAdsDisabled` hook makes
 * `VideoAdManager.ShowVideoClip` take the app's own "ads disabled" branch, which
 * invokes `RewardedVideoCompleted(completed=true, skipped=true)` in-process —
 * the stored completion action fires and the reward lands instantly with no ad,
 * no mediation, no Java callback to wait on (so it cannot hang). Blocking
 * `UnityBridge.showRewarded` instead would need a real ad callback object and
 * would strand the C# wait. Keep the native engine (Unlimited Currency Engine
 * + Trigger, both default-on) enabled for instant rewards; with this patch alone,
 * opt-in "watch ad" offers still behave like the stock game (real ad, real reward).
 *
 * Disjoint from the engine's native gates (different layer, no shared state);
 * safe to enable alongside the currency patches. Anchors: notes/ads.md A1/A4.
 */
@Suppress("unused")
val adsRemovalPatch = bytecodePatch(
    name = "Swamp Attack 2: Remove Ads",
    description = "Removes all ads. No more forced ads between levels, no banners. When the game offers a reward for watching an ad, you still get the reward.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK_2)

    execute {
        // Interstitial display
        UnityBridgeShowInterstitialFingerprint.method.returnEarly()

        // Banner display (create loads+attaches, show attaches, load fetches)
        UnityBridgeCreateBannerWithPositionFingerprint.method.returnEarly()
        UnityBridgeCreateBannerWithCoordsFingerprint.method.returnEarly()
        UnityBridgeShowBannerFingerprint.method.returnEarly()
        UnityBridgeLoadBannerFingerprint.method.returnEarly()

        // MREC display
        UnityBridgeCreateMrecWithPositionFingerprint.method.returnEarly()
        UnityBridgeCreateMrecWithCoordsFingerprint.method.returnEarly()
        UnityBridgeShowMrecFingerprint.method.returnEarly()
        UnityBridgeLoadMrecFingerprint.method.returnEarly()
    }
}

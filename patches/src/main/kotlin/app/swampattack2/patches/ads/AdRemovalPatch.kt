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
 *   never reaches the SDK.
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
 * **Rewarded is deliberately NOT touched here.** Blocking `UnityBridge.showRewarded`
 * would need a real ad callback object and could strand the C# wait for the
 * reward. Rewarded offers are instead completed in-engine by the static
 * `InstantRewardedPatch` (forces `PlayerData.AreVideoAdsDisabled` true, so
 * `VideoAdManager.ShowVideoClip` takes the app's own ads-disabled branch and
 * grants instantly): with both patches, opt-in "watch ad" offers grant
 * immediately with no ad.
 *
 * Standalone: no trigger, no native companion — safe alongside the static
 * currency patch. Anchors: notes/ads.md A1/A4.
 */
@Suppress("unused")
val adsRemovalPatch = bytecodePatch(
    name = "Swamp Attack 2: Remove Ads",
    description = "Removes forced ads. No more ads between levels, no banners.",
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

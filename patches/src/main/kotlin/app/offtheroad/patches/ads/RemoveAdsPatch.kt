package app.offtheroad.patches.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.offtheroad.patches.shared.Constants.COMPATIBILITY_OFF_THE_ROAD

/**
 * Off The Road 1.18.2 · **Remove Ads** — interstitials and banners, nothing else.
 *
 * Pure DEX on the two Java ad bridges. Native `cApplication::showFullScreenAd`
 * (`libgame.so` `0x3755cc`) checks the ads-disabled flag at `+0x54` and then calls the
 * `cAdmob` / `cUnityAds` singletons, which `CallVoidMethod`/`CallBooleanMethod` into
 * these methods:
 *
 * | Method | Action |
 * |---|---|
 * | `Admob.ShowInterstitial()Z` | return `false` |
 * | `Admob.InitBanner()V` | return (no banner view) |
 * | `Admob.SetBannerVisible(Z)V` | return (never shows one) |
 * | `Admob.CacheInterstitial()V` | return (stop preloading) |
 * | `UnityAds.showInterstitial()Z` | return `false` |
 * | `UnityAds.loadInterstitial()V` | return (stop preloading) |
 *
 * **Rewarded ads are deliberately untouched**: this patch does *not* grant the
 * `removeads` product, so `cApplication+0x54` stays 0 and
 * `isRewardedVideoAdAvailable()` continues to work. That is why this is independent of
 * "Free Store" (which turns every ad off, including rewarded, once the player buys
 * `removeads` for free) and can be combined with "Free Rewarded Ads".
 *
 * No native edit: all four ABIs are covered by the same DEX patch.
 */
@Suppress("unused")
val offTheRoadRemoveAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Removes full-screen and banner ads. Rewarded \"watch a video\" buttons " +
        "keep working.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_OFF_THE_ROAD)

    execute {
        AdmobShowInterstitialFingerprint.method.returnEarly(false)
        AdmobInitBannerFingerprint.method.returnEarly()
        AdmobSetBannerVisibleFingerprint.method.returnEarly()
        AdmobCacheInterstitialFingerprint.method.returnEarly()
        UnityAdsShowInterstitialFingerprint.method.returnEarly(false)
        UnityAdsLoadInterstitialFingerprint.method.returnEarly()
    }
}

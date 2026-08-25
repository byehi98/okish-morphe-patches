package app.adda247.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.adda247.patches.shared.Constants.COMPATIBILITY_ADDA247

/**
 * Adda247 — Ad Removal
 *
 * Adda247 is a Flutter app. Whether/which ad to show is decided in Dart (server-driven
 * config) and is NOT reachable from DEX, but the entire load/show machinery is the
 * Google Mobile Ads Flutter plugin, whose Java layer IS in DEX. Every ad operation Dart
 * requests travels through ONE method — the plugin's MethodChannel handler:
 *
 *   io.flutter.plugins.googlemobileads.n.onMethodCall(Lu00/h;Lu00/i$d;)V
 *   (jadx: C19948n; confirmed classes6/io/flutter/plugins/googlemobileads/n.smali:535)
 *
 * Patching that single dispatcher kills BOTH halves of the ad lifecycle, which is why no
 * per-ad-class patch is needed (and none is possible here: R8 renamed FlutterBannerAd,
 * FlutterInterstitialAd, ... to `Lio/flutter/plugins/googlemobileads/a;`, `b;`, `c;` ...,
 * so they cannot be fingerprinted by name the way an unobfuscated build allows):
 *
 *  1. LOAD KILL — any method name starting with "load" is swallowed. In this build that is
 *     exactly the ad loaders and nothing else (verified against the full dispatch table:
 *     loadBannerAd, loadInterstitialAd, loadRewardedAd, loadRewardedInterstitialAd,
 *     loadNativeAd, loadAppOpenAd, loadFluidAd, loadAdManagerBannerAd,
 *     loadAdManagerInterstitialAd — every other case is `_init`, `disposeAd`, `getAdSize`,
 *     `setImmersiveMode`, `setServerSideVerificationOptions`,
 *     `AdSize#getAnchoredAdaptiveBannerAdSize`, `InvalidRequest` or `MobileAds#*`).
 *     Nothing is ever requested from the GMA SDK — no network call, no fill, no impression.
 *     A "load" prefix test is used instead of nine string compares so the block stays small
 *     and automatically covers loadFluidAd.
 *
 *  2. SHOW KILL — "showAdWithoutView" is swallowed. That is the single native entry point
 *     for every full-screen format (interstitial, rewarded, rewarded-interstitial and
 *     app-open); banners/native ads have no show call because they render through the
 *     platform view. Belt-and-braces: even if some path ever produced a loaded ad, it can
 *     never be displayed.
 *
 * Every other method — `_init`, `disposeAd`, `getAdSize`, `MobileAds#initialize`, the
 * request-configuration getters/setters — falls through UNTOUCHED, so plugin startup and
 * Dart-side disposal keep working normally and nothing stalls.
 *
 * The intercepted calls answer `result.success(null)` (NOT `error(...)`, NOT
 * `notImplemented()`), which is exactly what the real handlers return on this channel.
 * The Dart API declares these as `Future<void>`, so the Dart futures complete normally and
 * no `PlatformException` / `MissingPluginException` is ever thrown. Because
 * `onAdLoaded`/`onAdFailedToLoad` are never dispatched back, the Dart ad objects simply stay
 * un-loaded forever and the app quietly renders no ad.
 *
 * Crash safety of the load kill was verified downstream: skipping the loader also skips
 * `AdInstanceManager.trackAd`, so a banner `AdWidget` built without waiting for
 * `onAdLoaded` asks the platform-view factory for an untracked id. That factory is
 * defensive — `Le10/u;->create` falls back to `Le10/u;->a(Landroid/content/Context;I)`, which
 * logs "This ad may have not been loaded or has been disposed..." and returns `Le10/u$a;`,
 * a PlatformView whose `getView()` is a plain empty `android.view.View`
 * (verified in e10/u.smali and e10/u$a.smali). Worst case is a blank gap, never a crash.
 * `disposeAd` is likewise null-safe (`a.smali:163` guards with `Map.containsKey` before
 * `remove`), so disposing an ad that never loaded is a no-op.
 *
 * INJECTION (index 0, before the original prologue):
 * `.registers 23` with 3 parameters → p0=v20, p1=v21 (MethodCall), p2=v22 (Result), and
 * v0..v19 are true locals that are still uninitialized at method entry. The original
 * prologue's first three instructions are `move-object/from16 v0, p0` / `v1, p1` /
 * `v2, p2`, so it re-establishes v0/v1/v2 itself — clobbering v0/v1 as scratch is therefore
 * free, and p0/p1/p2 are never written, so the fall-through path (`:morphe_adda247_original`)
 * sees a pristine frame. p1/p2 live above v15 and cannot be used as invoke/iget operands
 * directly, hence the `move-object/from16` into v0 first. There is no try/catch and no
 * monitor anywhere in onMethodCall (verified), so the sparse-switch below stays reachable
 * and the verifier is satisfied.
 */
@Suppress("unused")
val adda247AdsPatch = bytecodePatch(
    name = "Adda247 Ad Removal",
    description = "Blocks all Google AdMob ads (banner, interstitial, rewarded, rewarded interstitial, native, app open and Ad Manager) by neutralizing the Flutter Google Mobile Ads plugin: no ad is ever requested and no ad can ever be shown.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADDA247)

    execute {
        // Swallow every load*Ad and the showAdWithoutView dispatch at the very top of the
        // plugin's MethodChannel handler; everything else falls through to the original body.
        GoogleMobileAdsOnMethodCallFingerprint.method.addInstructionsWithLabels(0, """
            move-object/from16 v0, p1
            iget-object v0, v0, Lu00/h;->a:Ljava/lang/String;
            if-eqz v0, :morphe_adda247_original
            const-string v1, "load"
            invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v1
            if-nez v1, :morphe_adda247_block
            const-string v1, "showAdWithoutView"
            invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v1
            if-eqz v1, :morphe_adda247_original
            :morphe_adda247_block
            move-object/from16 v0, p2
            if-eqz v0, :morphe_adda247_swallow
            const/4 v1, 0x0
            invoke-interface {v0, v1}, Lu00/i${'$'}d;->success(Ljava/lang/Object;)V
            :morphe_adda247_swallow
            return-void
            :morphe_adda247_original
            nop
        """.trimIndent())
    }
}

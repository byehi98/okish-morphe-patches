package app.crazygames.patches.ads

import app.crazygames.patches.shared.Constants.COMPATIBILITY_CRAZYGAMES
import app.morphe.patcher.patch.rawResourcePatch

/**
 * CrazyGames 1.7.13 — remove ads and grant ad rewards without watching.
 *
 * WHY JAVASCRIPT, NOT SMALI: the app is a Capacitor wrapper around a bundled Next.js
 * portal (`assets/public`) plus the CrazyGames *gameframe* (`assets/public/gameframe/`).
 * Ads are Web/JS-driven; the only native part is Google AdMob, which the portal calls
 * via `@crazygames/capacitor-admob`. The full chain (verified in the 1.7.13 bundle):
 *
 *   game iframe            CrazyGames JS SDK (sdk.crazygames.com/crazygames-sdk-v3.js)
 *     |  postMessage({type:"requestAd", data:{adType}})
 *     v
 *   gameframe window       gameframe/bundle.js -> JSSDK.receiveMessage -> handleRequestAd
 *     |                     -> mounts <NativeVideo> -> GFEvents.nativeVideo(videoType, play)
 *     |  window.parent.postMessage({type:"<GF event>", event:"nativeVideo", ...})
 *     v
 *   portal (top level)     handleNativeVideo -> showNativeVideoAd / prepareNativeVideoAd
 *     |                     -> Capacitor AdMob Plugin (prepareRewardVideoAd / showRewardVideoAd)
 *     |  AdMob Shown/Dismissed listeners
 *     v  window.postMessage({type:"NativeAdEvent", data:{action:"onAdStarted"|"onAdFinished"}})
 *   gameframe window       <NativeVideo> listener -> JSSDK.onAdStarted / onAdFinished
 *     |  postMessage({type:"adStarted"|"adCompleted"|"adFinished", messageTarget:"sdk"})
 *     v
 *   game iframe            SDK fires adStarted/adFinished callbacks -> reward granted
 *
 * In the native app only `midroll` and `rewarded` are allowed
 * (`adsAllowedForSession`: `isApp && type !== midroll && type !== rewarded` is denied,
 * and `portalBanner`/`preroll` are desktop/launch-flow gated), so *every* ad in this app
 * originates from a game `requestAd`. Cutting that one message removes all of them.
 *
 * WHY THIS INJECTION POINT: `MainActivity` reads `assets/ExternalPaymentScriptShared.js`
 * and `assets/ExternalPaymentScript.js` and concatenates both into ONE document-start
 * script, `(function () { const nativeValues = {...}; <shared> \n <payment> \n})();`,
 * registered with `WebViewCompat.addDocumentStartJavaScript(..., pr0.f)` where `pr0.f`
 * is `setOf("*")` (1.7.14: `smali_classes2/com/crazygames/crazygamesapp/MainActivity.smali`
 * around the `addDocumentStartJavaScript` call; the origins set is built in
 * `smali_classes2/pr0.smali`, `<clinit>`, last `sput-object` into `f`). So the file runs
 * in every document of the WebView - portal, gameframe and game iframes - before any
 * page script. That lets us add a capture-phase `message` listener that runs before the
 * gameframe's own listener. The asset also lives outside `webDir`, so a LiveUpdate OTA
 * cannot overwrite it.
 *
 * WHAT THE SHIM DOES: when a game posts `requestAd` (or the legacy
 * `window.Crazygames.requestAd`), the shim stops the event before the gameframe can
 * forward it to the portal and answers the sender directly with the exact event shapes
 * the game-side SDK already understands:
 *   - JS SDK v2/v3: `{type:"adStarted"|"adCompleted"|"adFinished", messageTarget:"sdk"}`
 *     (mirrors JSSDK.postMessageToSdk; `adFinished` is what resolves the SDK callback
 *     and what games hook to grant the reward)
 *   - legacy Unity bridge: `{type:"sendUnityMessage", methodName:"AdEvent",
 *     value:'{"name":"adStarted"|"adCompleted"|"adFinished"}'}` (mirrors UnitySDK)
 * Because the gameframe never sees the request, `<NativeVideo>` never mounts, the portal
 * is never told to `nativeVideo`, and AdMob is never asked to prepare or show anything -
 * no fullscreen video, no interstitial, no preload traffic.
 *
 * LIMITS (by design / server-side):
 *   - Portal-level rewards validated by CrazyGames own backend (tickets, ad-free promos)
 *     are not routed through this game `requestAd` path and are left untouched.
 *   - Games that validate the reward with a server-side ad SSV token will still reject
 *     the locally granted reward even though the ad was "finished".
 */
@Suppress("unused")
val crazyGamesRemoveAdsPatch = rawResourcePatch(
    name = "Remove ads and unlock ad rewards",
    description = "Suppresses every midroll and rewarded ad (native AdMob and Web ad " +
        "providers) and answers the game's ad request locally, so rewards are granted " +
        "without watching a video. No ad request ever reaches the portal or AdMob.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_CRAZYGAMES)

    execute {
        val asset = get("assets/ExternalPaymentScript.js", true)
        val original = asset.readText()
        val marker = "/* crazygames-ads-patch:v1 */"

        if (original.contains(marker)) {
            println("CrazyGames ads: shim already present in assets/ExternalPaymentScript.js")
            return@execute
        }

        asset.appendText("\n\n$marker\n$AD_SHIM\n")
        println(
            "CrazyGames ads: appended ${AD_SHIM.length} B shim to " +
                "assets/ExternalPaymentScript.js (${original.length} -> ${original.length + AD_SHIM.length} B)"
        )
    }
}

/**
 * Document-start shim. Kept in a raw string so it can be reviewed next to the patch that
 * installs it. It must stay ES5-compatible-ish (WebView >= 103, so modern syntax is fine,
 * but no bundler/imports). No `$` characters, so nothing has to be escaped in Kotlin.
 */
private const val AD_SHIM = """
(function () {
  "use strict";
  if (window.__crazygamesAdsPatch) return;
  window.__crazygamesAdsPatch = true;

  var TAG = "[cg-ads-patch]";

  function post(target, payload) {
    try {
      target.postMessage(payload, "*");
    } catch (e) {
      try { target.postMessage(payload); } catch (e2) {}
    }
  }

  // Answer in both wire formats the gameframe uses: JS SDK v2/v3 and the legacy
  // Unity bridge. Each side ignores the other's message type.
  function grant(target, adType) {
    console.log(TAG, "ad request intercepted, granting reward without a video:", adType);
    post(target, { type: "adStarted", messageTarget: "sdk" });
    post(target, {
      type: "sendUnityMessage",
      methodName: "AdEvent",
      value: JSON.stringify({ name: "adStarted" })
    });
    setTimeout(function () {
      post(target, { type: "adCompleted", messageTarget: "sdk" });
      post(target, { type: "adFinished", messageTarget: "sdk" });
      post(target, {
        type: "sendUnityMessage",
        methodName: "AdEvent",
        value: JSON.stringify({ name: "adCompleted" })
      });
      post(target, {
        type: "sendUnityMessage",
        methodName: "AdEvent",
        value: JSON.stringify({ name: "adFinished" })
      });
    }, 400);
  }

  // Capture phase: runs before the gameframe bundle's own window listener, so
  // stopImmediatePropagation prevents the request from reaching the portal.
  window.addEventListener("message", function (event) {
    var data = event.data;
    if (!data || typeof data !== "object") return;
    if (data.messageTarget === "sdk") return;

    var adType = null;
    if (data.type === "requestAd" && data.data && typeof data.data.adType === "string") {
      adType = data.data.adType;
    } else if (data.type === "window.Crazygames.requestAd" && typeof data.adType === "string") {
      adType = data.adType;
    } else {
      return;
    }
    if (!event.source) return;

    event.stopImmediatePropagation();
    grant(event.source, adType);
  }, true);
})();
"""

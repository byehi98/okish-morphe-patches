package app.adda247.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Mobile Ads Flutter plugin — Dart → native dispatch ─────────────────
// Adda247 is a Flutter app: every ad *decision* is made in Dart, but the whole
// load/show machinery is the google_mobile_ads plugin, whose Java layer IS in DEX.
// The single choke point is the plugin's MethodCallHandler.onMethodCall, which
// dispatches every ad operation the Dart side requests.

/**
 * io.flutter.plugins.googlemobileads.n.onMethodCall(Lu00/h;Lu00/i$d;)V — public.
 *
 * The GoogleMobileAdsPlugin `MethodChannel.MethodCallHandler` (jadx: `C19948n`) for the
 * "plugins.flutter.io/google_mobile_ads" channel (registered in onAttachedToEngine —
 * confirmed n.smali:275). Its body is a single `hashCode()` + `sparse-switch` +
 * `String.equals` dispatch over EVERY ad operation Dart can request:
 *
 *   load*  : loadBannerAd, loadInterstitialAd, loadRewardedAd, loadRewardedInterstitialAd,
 *            loadNativeAd, loadAppOpenAd, loadFluidAd, loadAdManagerBannerAd,
 *            loadAdManagerInterstitialAd
 *   show   : showAdWithoutView (interstitial / rewarded / rewarded-interstitial / app-open)
 *   other  : _init, disposeAd, getAdSize, setImmersiveMode, setServerSideVerificationOptions,
 *            AdSize#getAnchoredAdaptiveBannerAdSize, MobileAds#* (initialize, setAppVolume,
 *            setAppMuted, getVersionString, openAdInspector, openDebugMenu, registerWebView,
 *            updateRequestConfiguration, getRequestConfiguration,
 *            disableMediationInitialization)
 *
 * Confirmed smali: classes6/io/flutter/plugins/googlemobileads/n.smali:535 (.registers 23 —
 * v0..v19 locals + p0=v20, p1=v21 (MethodCall), p2=v22 (Result); NO try/catch and NO monitor
 * anywhere in the method, so an index-0 injection is verifier-safe).
 *
 * `call.method` is read at n.smali:584 as `iget-object v5, v1, Lu00/h;->a:Ljava/lang/String;`
 * (v1 == p1 after the `move-object/from16 v1, p1` prologue). `Lu00/h;->a` is `public final`
 * (u00/h.smali:7), so a direct `iget-object` from the patch is legal.
 *
 * MATCH STRATEGY — the plugin package/class/method names come from a third-party SDK and are
 * NOT obfuscated at the package level (`io/flutter/plugins/googlemobileads/...`), but R8 DID
 * rename the handler class to `n` and the Flutter channel types to `u00/h` / `u00/i$d`.
 * Because the injected code has to reference those renamed types by name anyway, the
 * fingerprint is deliberately pinned to the 12.6.4-604 mapping (definingClass `n`, parameters
 * `Lu00/h;`/`Lu00/i$d;`) — pinning adds no fragility the patch does not already have, and it
 * makes the match fast and unambiguous (`n` has exactly one `onMethodCall`).
 *
 * Filters are in EXACT instruction order (verified against n.smali line numbers). All filters
 * default to `MatchAfterAnywhere`, so each must appear after the previous one — the order below
 * is the real sparse-switch case order (R8 emits cases sorted by String.hashCode, which is
 * stable for a fixed set of method names):
 *
 *   599  const-string "loadRewardedInterstitialAd"
 *   601  invoke-virtual String->equals
 *   647  const-string "showAdWithoutView"
 *   649  invoke-virtual String->equals
 *   759  const-string "loadAdManagerBannerAd"
 *   807  const-string "loadBannerAd"
 *   823  const-string "loadAdManagerInterstitialAd"
 *   871  const-string "loadNativeAd"
 *   919  const-string "loadRewardedAd"
 *   951  const-string "loadAppOpenAd"
 *   983  const-string "loadInterstitialAd"
 *
 * The two `String->equals` filters pin the switch-dispatch shape (a plain string table would
 * not have them); the ad-method strings together make a false positive impossible — no other
 * class in the app carries this exact set.
 */
object GoogleMobileAdsOnMethodCallFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/n;",
    name = "onMethodCall",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lu00/h;", "Lu00/i\$d;"),
    filters = listOf(
        string("loadRewardedInterstitialAd"),
        methodCall(
            definingClass = "Ljava/lang/String;",
            name = "equals",
        ),
        string("showAdWithoutView"),
        methodCall(
            definingClass = "Ljava/lang/String;",
            name = "equals",
        ),
        string("loadAdManagerBannerAd"),
        string("loadBannerAd"),
        string("loadAdManagerInterstitialAd"),
        string("loadNativeAd"),
        string("loadRewardedAd"),
        string("loadAppOpenAd"),
        string("loadInterstitialAd"),
    )
)

// ── MoEngage STANDARD native in-app messaging — HTML render + attach ──────────
// A SEPARATE surface from Google Mobile Ads above. MoEngage In-App 10.2.0 is a
// marketer-console engagement SDK that is fully DEX-resident. Its "standard"
// (non self-handled) campaigns are rendered natively by MoEngage's own
// `com.moengage.inapp.internal.html.InAppWebView`, which is the recurring
// "recommended for you" nudge with no close button (a NON_INTRUSIVE campaign).
// See analysis/com.adda247.app/notes/app-owned-ads.md, Surface 1.

/**
 * The `InApp_10.2.0_HtmlViewEngine` class — MoEngage's abstract HTML view engine
 * (R8-renamed to `Lkv/i0;` in 12.6.4-604; jadx: `p311kv.AbstractC22653i0`).
 *
 * Used ONLY as a `classFingerprint` anchor for the render method below, so the
 * obfuscated class name never has to be hardcoded.
 *
 * The engine's log tag is assigned in its constructor:
 *
 *   kv/i0.smali:74   const-string p1, "InApp_10.2.0_HtmlViewEngine"
 *   kv/i0.smali:78   iput-object p1, p0, Lkv/i0;->g:Ljava/lang/String;
 *
 * IMPORTANT CORRECTION to app-owned-ads.md fingerprint (B): that note lists
 * `string("InApp_10.2.0_HtmlViewEngine")` as a filter of the render method `Q`.
 * It is NOT in `Q` — verified by reading the full method body (kv/i0.smali:975-1404).
 * The literal lives ONLY in `<init>`; inside `Q` the tag is reached indirectly via
 * `iget-object Lkv/i0;->g` from synthetic log lambdas (`kv/b0`, `kv/c0`), which are
 * separate classes. Filtering `Q` on that string would therefore never match — hence
 * the string is used here, at CLASS level, which is both correct and the most durable
 * anchor available (it is version-pinned to the MoEngage SDK, not to R8's naming).
 *
 * Uniqueness: `rg -c 'InApp_10\.2\.0_HtmlViewEngine'` over the whole APK returns
 * exactly ONE hit — classes5/kv/i0.smali:74 — so this resolves to a single class.
 */
object MoEngageHtmlViewEngineClassFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        string("InApp_10.2.0_HtmlViewEngine"),
    )
)

/**
 * InApp_10.2.0_HtmlViewEngine.Q(Ljava/lang/String;Landroid/view/ViewGroup;)V — public final.
 * (R8: `Lkv/i0;->Q`; jadx: `p311kv.AbstractC22653i0.m90723Q`.)
 *
 * THE single sink for every standard (HTML) MoEngage in-app message. It builds the
 * `com.moengage.inapp.internal.html.InAppWebView`, wires the JS bridge, loads the
 * server-supplied campaign HTML and attaches the WebView to the campaign container:
 *
 *   1037  invoke-direct  Lcom/moengage/inapp/internal/html/InAppWebView;-><init>(Landroid/content/Context;)V
 *   1181  invoke-direct  Lcom/moengage/inapp/internal/html/InAppWebViewClient;-><init>(Lrv/l;)V
 *   1224  invoke-virtual Landroid/webkit/WebView;->addJavascriptInterface(...)   // "moengageInternal"
 *   1249  const-string   v11, "text/html"
 *   1260  invoke-virtual Landroid/webkit/WebView;->loadDataWithBaseURL(...)V     // campaign HTML
 *   1329  invoke-virtual Landroid/view/ViewGroup;->addView(Landroid/view/View;)V // on-screen
 *
 * Both concrete engines funnel here — `Lkv/a0;` (NON_INTRUSIVE nudge, i.e. the
 * "recommended for you" banner) and `Lkv/r;` (full-screen standard in-app). Each
 * builds its container in `E()`, then calls `Lkv/i0;->F(ViewGroup, String)`, which
 * posts a `Lkv/h0;` runnable to the UI thread; that runnable calls the synthetic
 * `z` → `G` → `Q`. Verified: `Lkv/i0;->Q(` has exactly ONE call site app-wide
 * (kv/i0.smali:252, inside `G`), so `Q` cannot be bypassed.
 *
 * Confirmed smali: classes5/kv/i0.smali:975 —
 *   `.registers 17` with 3 parameter registers → v0..v13 are true locals and
 *   p0=v14, p1=v15 (String assetsPath), p2=v16 (ViewGroup container).
 *   There are NO monitor-enter/monitor-exit instructions anywhere in kv/i0.smali,
 *   so an index-0 injection cannot strand a lock. The method IS wrapped in
 *   `.catchall {:try_start_0 .. :try_end_b3} :catchall_ac` whose `:try_start_0`
 *   label sits on the FIRST instruction — but dexlib2 binds try-block boundaries to
 *   the `MethodLocation` of their instruction, so inserting at index 0 pushes the
 *   try region down and leaves the injected `return-void` OUTSIDE it. A bare
 *   `return-void` reads no registers at all, so no parameter or local has to be
 *   re-established. See MoEngageInAppPatch for the full verifier argument.
 *
 * MATCH STRATEGY — deliberately built only from NON-obfuscated anchors:
 *  - class located via MoEngageHtmlViewEngineClassFingerprint (the SDK version
 *    string), never via the R8 name `Lkv/i0;`;
 *  - the method itself is pinned by shape, never by its obfuscated name `Q`:
 *    `public final`, returns `V`, parameters `(String, ViewGroup)` — inside the
 *    engine class the only other `public final` V method taking those two types is
 *    `F(ViewGroup, String)`, whose parameters are in the OPPOSITE order, so the
 *    signature alone already disambiguates;
 *  - filters are real MoEngage SDK class names (`InAppWebView`, `InAppWebViewClient`)
 *    plus framework calls — none of them are obfuscated, so they survive R8 reruns.
 *
 * Filters are in EXACT instruction order (line numbers above, strictly increasing;
 * all default to `MatchAfterAnywhere`). `Lcom/moengage/inapp/internal/html/InAppWebView;`
 * is constructed in exactly ONE place in the entire APK (kv/i0.smali:1021/1037 —
 * grep-verified), so filter #1 on its own already makes a false positive impossible;
 * the remaining four pin the load+attach shape as belt-and-braces.
 *
 * NOTE: `Lcom/pichillilorenzo/flutter_inappwebview_android`'s unrelated `InAppWebView`
 * (classes6) is a DIFFERENT class and is not touched by these filters.
 */
object MoEngageHtmlViewEngineRenderFingerprint : Fingerprint(
    classFingerprint = MoEngageHtmlViewEngineClassFingerprint,
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/String;", "Landroid/view/ViewGroup;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/moengage/inapp/internal/html/InAppWebView;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/moengage/inapp/internal/html/InAppWebViewClient;",
            name = "<init>",
        ),
        string("text/html"),
        methodCall(
            definingClass = "Landroid/webkit/WebView;",
            name = "loadDataWithBaseURL",
        ),
        methodCall(
            definingClass = "Landroid/view/ViewGroup;",
            name = "addView",
        ),
    )
)

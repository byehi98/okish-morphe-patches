package app.swampattack2.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ---------------------------------------------------------------------------
// Swamp Attack 2 v1.3.10 — UnityBridge (Metica ↔ Unity ad-SDK bridge) fingerprints.
//
// All targets live in Lcom/metica/unity_bridge/UnityBridge; (smali/classes9/UnityBridge.smali)
// — the Medica SDK is NOT obfuscated, so names/parameters are stable. Every
// fingerprint below was cross-checked against the shipped smali body (line
// refs in each doc comment) including filter instruction ORDER.
//
// Access flags are matched EXACTLY (Fingerprint folds the list and compares
// the int with ==), so `declared-synchronized` methods must list
// DECLARED_SYNCHRONIZED (0x20000) — SYNCHRONIZED (0x20) is a different bit
// and would fail to resolve. baksmali prints every set flag, so the flag
// lists below are exactly what the smali headers show.
//
// Deliberately absent:
// * showRewarded / loadRewarded — rewarded is completed NATIVELY by the
//   static InstantRewardedPatch (AreVideoAdsDisabled → true, so the engine
//   grants the reward in-engine before any Java is reached); blocking the
//   Java method would need a real ad callback object and could hang the
//   C# wait.
// * hide*/destroy*/start*AutoRefresh/setWidth/update*Position — all operate
//   on an existing MeticaAdView; with create/load/show blocked no view ever
//   exists, so they are unreachable no-ops.
// * MaxUnityPlugin (AppLovin MAX direct bridge) — no game path reaches it
//   (notes/ads.md A6: C# goes through Metica/AdMediation only).
// ---------------------------------------------------------------------------

/** UnityBridge.showInterstitial (smali line 4195) — the interstitial display path.
 *  `public static final` (NOT synchronized): getAds() → getActivity() → MeticaAds.showInterstitial(...).
 *  Belt-and-braces with the native AreInterstitialsDisabled gate (which stops
 *  the C# side before it ever calls Java). */
object UnityBridgeShowInterstitialFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lcom/metica/ads/MeticaAdsShowCallback;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "showInterstitial",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/MeticaSdk;", name = "getAds"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getActivity"),
    ),
)

/** UnityBridge.showBanner (smali line 4060) — `declared-synchronized`,
 *  getBannerManager() → UnityBanners.show(adUnitId) (attaches + auto-refreshes the view). */
object UnityBridgeShowBannerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "showBanner",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getBannerManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "show"),
    ),
)

/** UnityBridge.showMrec (smali line 4274) — same shape as showBanner via getMrecManager(). */
object UnityBridgeShowMrecFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "showMrec",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getMrecManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "show"),
    ),
)

/** UnityBridge.loadBanner (smali line 1617) — getBannerManager() → UnityBanners.load(adUnitId).
 *  Never creates a view itself (load is a no-op when no view exists), but blocked so no
 *  banner request ever fires. */
object UnityBridgeLoadBannerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "loadBanner",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getBannerManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "load"),
    ),
)

/** UnityBridge.loadMrec (smali line 1767) — same shape via getMrecManager(). */
object UnityBridgeLoadMrecFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "loadMrec",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getMrecManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "load"),
    ),
)

/** UnityBridge.createBannerWithPosition (smali line 395) — the path that CREATES, loads
 *  and ATTACHES a banner view (UnityBanners.createAdView → retrieveOrCreateAdView + load()
 *  + attach lambda). Blocking show* alone would leave this path able to put a banner on
 *  screen, so the create* methods must go too. */
object UnityBridgeCreateBannerWithPositionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lcom/metica/ads/MeticaAdsAdViewCallback;",
    ),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "createBannerWithPosition",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners\$AdViewPosition\$Companion;",
            name = "fromString",
        ),
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getBannerManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "createAdView"),
    ),
)

/** UnityBridge.createBannerWithCoords (smali line 340) — same as above with pixel coords
 *  (no AdViewPosition.fromString step: null-checks → getBannerManager → createAdView). */
object UnityBridgeCreateBannerWithCoordsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "D",
        "D",
        "Lcom/metica/ads/MeticaAdsAdViewCallback;",
    ),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "createBannerWithCoords",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getBannerManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "createAdView"),
    ),
)

/** UnityBridge.createMrecWithPosition (smali line 506) — MREC create/load/attach path
 *  (fromString → getMrecManager → createAdView). */
object UnityBridgeCreateMrecWithPositionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lcom/metica/ads/MeticaAdsAdViewCallback;",
    ),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "createMrecWithPosition",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners\$AdViewPosition\$Companion;",
            name = "fromString",
        ),
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getMrecManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "createAdView"),
    ),
)

/** UnityBridge.createMrecWithCoords (smali line 451) — getMrecManager → createAdView. */
object UnityBridgeCreateMrecWithCoordsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "D",
        "D",
        "Lcom/metica/ads/MeticaAdsAdViewCallback;",
    ),
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.STATIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    definingClass = "Lcom/metica/unity_bridge/UnityBridge;",
    name = "createMrecWithCoords",
    filters = listOf(
        methodCall(definingClass = "Lcom/metica/unity_bridge/UnityBridge;", name = "getMrecManager"),
        methodCall(definingClass = "Lcom/metica/unity_bridge/internal/UnityBanners;", name = "createAdView"),
    ),
)

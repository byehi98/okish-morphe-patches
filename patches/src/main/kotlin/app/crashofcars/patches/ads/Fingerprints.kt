package app.crashofcars.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags

// ── AppLovin MAX 13.6.4 — the whole ad stack for Crash of Cars 1.9.34 ────────
//
// ⚠️ The ad stack is **AppLovin MAX**, *not* the Unity LevelPlay WebView ad
// player. Il2CppDumper + AArch64 disassembly of `libil2cpp.so` shows the game's
// entire ad abstraction is a hand-written C# wrapper around the MAX SDK:
//
//     com.notdoppler.ads.AdController
//       └─ IAdNetwork _adNetwork
//            ├─ MaxAdNetwork  → MaxSdkAndroid : MaxSdkBase : AndroidJavaProxy
//            └─ NullAdNetwork (dead code)
//       produced once by AdNetworkFactory.CreateAdNetwork (RVA 0x23BF070)
//
//     MaxAdNetwork::ShowInterstitial   RVA 0x23BDEC4 → MaxSdkAndroid::ShowInterstitial
//                                                          → MaxUnityAdManager.showInterstitial(...)
//     MaxAdNetwork::ShowRewardedVideo  RVA 0x23BDCC4 → MaxSdkAndroid::ShowRewardedAd
//                                                          → MaxUnityAdManager.showRewardedAd(...)
//
// So `Lcom/unity3d/ads/adplayer/*` (`CommonWebViewBridge`, `WebViewAdPlayer`,
// `AndroidFullscreenWebViewAdPlayer`) is a **mediated network *inside* AppLovin** —
// it is reached *through* MAX, so cutting the MAX show entry point also kills it,
// while patching the WebView player achieves nothing. Do not target it.
// See `analysis/com.notdoppler.crashofcars/notes/ad-patches.md` §0.
//
// `Lcom/applovin/mediation/unity/MaxUnityAdManager;` is the Unity-plugin facade:
// it is the last Java frame before the C# side, so it is one layer *above* the
// 9-deep `MaxRewardedAd`/`MaxInterstitialAd.showAd` overload sets and one layer
// below nothing.
//
// Every name below is an **AppLovin SDK name**, never a ProGuard-obfuscated one,
// and every descriptor occurs exactly once in the class (verified by `rg`), so
// these fingerprints are stable across AppLovin patch releases inside this game
// version. The two `methodCall` filters per fingerprint are pinned on the real
// instruction order of the shipped bodies — see each KDoc for the index map.
//
// `accessFlags` matching in Morphe folds the flags into an int and compares
// EXACTLY, so listing `AccessFlags.STATIC` / `AccessFlags.SYNCHRONIZED` here
// would silently resolve to zero matches. All three targets are plain
// `.method public …` instance methods — no extra flags.

// ── AppLovin MAX 13.6.4 type descriptors used by both ad patches ────────────
//
// ⚠️ **`MANAGER` is the only one that carries its trailing `;`.** It is used both
// as a Fingerprint `definingClass` (which needs the full descriptor) and in smali
// as `$MANAGER->method(…)`, where the `;` is likewise part of the descriptor. The
// other three are **bare type names**: in smali they must be terminated by the
// `;` written next to them in the instruction.
//
// Getting this wrong in either direction is a *lexer* error, not a parser one, so
// it is not caught by `./gradlew buildAndroid` — the inline smali compiler aborts
// the patch run with `Error for input ';': Invalid text`, e.g.
// `Lcom/applovin/mediation/MaxAd;;` from `"$MAX_AD;"` when `MAX_AD` already ended
// in `;`.

/** `Lcom/applovin/mediation/unity/MaxUnityAdManager;` — the MAX Unity facade. */
internal const val MANAGER = "Lcom/applovin/mediation/unity/MaxUnityAdManager;"

/** `Lcom/applovin/mediation/MaxAd` — the base ad type `getAd` returns. */
internal const val MAX_AD = "Lcom/applovin/mediation/MaxAd"

/** `Lorg/json/JSONObject` — the event payload type both helpers exchange. */
internal const val JSON_OBJECT = "Lorg/json/JSONObject"

/** `Lcom/applovin/impl/sdk/utils/JsonUtils` — static JSON writer, `public static`. */
internal const val JSON_UTILS = "Lcom/applovin/impl/sdk/utils/JsonUtils"

/**
 * `MaxUnityAdManager.showInterstitial(String, String, String)V` —
 * smali `classes/com/applovin/mediation/unity/MaxUnityAdManager.smali:7436`,
 * **`.registers 5`**, **`public`** instance method, **no exception table**,
 * **not synchronized** (no `monitor-enter` anywhere in the body), a single
 * label-free linear block of 6 instructions, and **exactly one caller**
 * (`MaxSdkAndroid::ShowInterstitial` via JNI — `UIEndScreen::SetupInterstitialAds`,
 * RVA `0x20C512C`, is the only C# site that dispatches `IAdNetwork.ShowInterstitial`,
 * interface slot 5). Filter → instruction index map, read off the real body:
 *
 * | # | Filter | Instruction index |
 * |---|---|---|
 * | 0 | `MaxUnityAdManager.retrieveInterstitial` | 0 (`invoke-direct {p0, p1}`) |
 * | 1 | `MaxInterstitialAd.showAd` | 4 (`invoke-virtual {p1, p2, p3, v0}`) |
 *
 * ```
 * :7436  .method public showInterstitial(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
 * :7437      .registers 5
 * :7439      invoke-direct {p0, p1}, …->retrieveInterstitial(Ljava/lang/String;)Lcom/applovin/mediation/ads/MaxInterstitialAd;
 * :7441      move-result-object p1
 * :7443      invoke-static  {},          …->getCurrentActivity()Landroid/app/Activity;
 * :7445      move-result-object v0
 * :7447      invoke-virtual {p1, p2, p3, v0}, Lcom/applovin/mediation/ads/MaxInterstitialAd;->showAd(…)V
 * :7449      return-void
 * ```
 *
 * This is the choke point for **ad removal**: it is the only place interstitials
 * enter the Java side, it is format-specific (so the same patch can leave
 * rewarded video alone), and it is **not** the entitlement path — it therefore
 * also applies to players who have *not* bought remove-ads. T6
 * (`MediationController.get_AdvertsDisabled`-style entitlement forcing) is
 * deliberately not used: forcing an entitlement getter bypasses the game's own
 * consume/reset cycle and produces the popup-loop regression catalogued in
 * `notes/community-catalog.md` §4.
 *
 * Deliberately NOT targeted:
 * - `MaxFullscreenAdImpl.showAd(String,String,Activity)V`
 *   (`classes/com/applovin/impl/mediation/ads/MaxFullscreenAdImpl.smali:3831`) —
 *   the only true single choke point over *both* formats, but it is
 *   format-agnostic, so a replacement body would have to branch on
 *   `MaxAd.getFormat()`, and it lives in AppLovin's obfuscated `impl` package
 *   (`class a` superclass, `u0()`/`l7()` internals).
 * - `MaxInterstitialAd.showAd` / `MaxRewardedAd.showAd` — 9 overloads each,
 *   9 different signatures: maximally fragile fingerprints for one behaviour.
 * - `MaxUnityAdManager.loadInterstitial(String)V` (`:6885`) —
 *   no-op'ing it would also stop the *load* loop, but the MAX SDK still needs
 *   the entry in `adInfoMap` for the injected body to be able to build an event
 *   payload (`getAd(adUnitId)`), so it is left alone.
 */
object MaxInterstitialShowFingerprint : Fingerprint(
    definingClass = MANAGER,
    name = "showInterstitial",
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;", // p1 — adUnitIdentifier
        "Ljava/lang/String;", // p2 — placement
        "Ljava/lang/String;"  // p3 — customData
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        methodCall(definingClass = MANAGER, name = "retrieveInterstitial"),
        methodCall(definingClass = "Lcom/applovin/mediation/ads/MaxInterstitialAd;", name = "showAd")
    )
)

/**
 * `MaxUnityAdManager.isRewardedAdReady(String)Z` —
 * smali `classes/com/applovin/mediation/unity/MaxUnityAdManager.smali:2834`,
 * **`.registers 2`** (locals `v0`, `p0=v1`), **`public`** instance method, **no
 * exception table**, **not synchronized**. Filter → instruction index map, read
 * off the real body:
 *
 * | # | Filter | Instruction index |
 * |---|---|---|
 * | 0 | `MaxUnityAdManager.retrieveRewardedAd` | 0 (`invoke-direct {p0, p1}`) |
 * | 1 | `MaxRewardedAd.isReady` | 2 (`invoke-virtual {p1}`) |
 *
 * ```
 * :2834  .method public isRewardedAdReady(Ljava/lang/String;)Z
 * :2835      .registers 2
 * :2837      invoke-direct  {p0, p1}, …->retrieveRewardedAd(Ljava/lang/String;)Lcom/applovin/mediation/ads/MaxRewardedAd;
 * :2839      move-result-object p1
 * :2841      invoke-virtual  {p1},      Lcom/applovin/mediation/ads/MaxRewardedAd;->isReady()Z
 * :2843      move-result p1
 * :2845      return p1
 * ```
 *
 * Both suggested `methodCall` filters were checked against the binary and are
 * present at exactly those indices, so the filter-based form is used rather than
 * the name/descriptor-only fallback.
 *
 * This is a **hard gate**, not a nicety. Two independent checks sit in front of
 * the rewarded-ad path and both consult it:
 * - `MaxAdNetwork.get_RewardVideoAvailable` (RVA `0x23BD3AC`) returns
 *   `MaxSdkAndroid::IsRewardedAdReady(defaultVideoAd)`; `AdController::PlayAd`
 *   (RVA `0x23BCA94`) checks it and otherwise launches `WaitingVideoLoading`
 *   (RVA `0x23BCE98`) — a blocking "waiting for video" popup that never clears,
 *   because nothing ever loads an ad once the show call is a no-op.
 * - `MaxSdkAndroid::ShowRewardedAd` (RVA `0x37088A4`) re-checks
 *   `IsRewardedAdReady` at `+0x9C` and bails (`tbz w0, #0, 0x3708A48`). Without
 *   this hook the `showRewardedAd` injection is **never reached at all**.
 *
 * `.registers 2` already provides everything the replacement needs
 * (`const/4 v0, 0x1` / `return v0`), so this method's frame is **not** grown.
 */
object MaxRewardedAdReadyFingerprint : Fingerprint(
    definingClass = MANAGER,
    name = "isRewardedAdReady",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        methodCall(definingClass = MANAGER, name = "retrieveRewardedAd"),
        methodCall(definingClass = "Lcom/applovin/mediation/ads/MaxRewardedAd;", name = "isReady")
    )
)

/**
 * `MaxUnityAdManager.showRewardedAd(String, String, String)V` —
 * smali `classes/com/applovin/mediation/unity/MaxUnityAdManager.smali:7465`,
 * **`.registers 5`**, **`public`** instance method, **no exception table**,
 * **not synchronized**, a single label-free linear block of 6 instructions, and
 * **exactly one caller** (`MaxSdkAndroid::ShowRewardedAd` via JNI, called from
 * `MaxAdNetwork::ShowRewardedVideo`, RVA `0x23BDCC4`). Filter → instruction
 * index map, read off the real body:
 *
 * | # | Filter | Instruction index |
 * |---|---|---|
 * | 0 | `MaxUnityAdManager.retrieveRewardedAd` | 0 (`invoke-direct {p0, p1}`) |
 * | 1 | `MaxRewardedAd.showAd` | 4 (`invoke-virtual {p1, p2, p3, v0}`) |
 *
 * ```
 * :7465  .method public showRewardedAd(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
 * :7466      .registers 5
 * :7468      invoke-direct {p0, p1}, …->retrieveRewardedAd(Ljava/lang/String;)Lcom/applovin/mediation/ads/MaxRewardedAd;
 * :7470      move-result-object p1
 * :7472      invoke-static  {},          …->getCurrentActivity()Landroid/app/Activity;
 * :7474      move-result-object v0
 * :7476      invoke-virtual {p1, p2, p3, v0}, Lcom/applovin/mediation/ads/MaxRewardedAd;->showAd(…)V
 * :7478      return-void
 * ```
 *
 * Both suggested `methodCall` filters were checked against the binary and are
 * present at exactly those indices.
 *
 * Structurally identical to [`MaxInterstitialShowFingerprint`] but for the
 * rewarded format, and reached through a different C# entry point —
 * `AdController::PlayAd` (RVA `0x23BCA94`) → `IAdNetwork.ShowRewardedVideo`
 * (interface slot 4), driven by `UIGemPackWatchAd::OnPressed`,
 * `UIGemsPackWatchAd::OnPressed`, `UILivesShop::OnWatchAdPressed` and
 * `UITeamMatch::OnWatchVideoPressed`.
 *
 * Pinned separately from `isRewardedAdReady` because the two halves of
 * "instant reward" fail differently: without A1 the show body is dead code
 * (above), and without A2 the buttons would report an ad they never played.
 */
object MaxRewardedAdShowFingerprint : Fingerprint(
    definingClass = MANAGER,
    name = "showRewardedAd",
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;", // p1 — adUnitIdentifier
        "Ljava/lang/String;", // p2 — placement
        "Ljava/lang/String;"  // p3 — customData
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        methodCall(definingClass = MANAGER, name = "retrieveRewardedAd"),
        methodCall(definingClass = "Lcom/applovin/mediation/ads/MaxRewardedAd;", name = "showAd")
    )
)

/**
 * Re-reads the method **out of its class** after it has been swapped, and
 * fails the whole patch if its frame is not exactly [expectedRegisters] words.
 *
 * `addInstructions(0, …)` cannot grow a method's `.registers` count at all —
 * `MutableMethodImplementation.registerCount` is a `final` field of the dexlib2
 * builder — so a body that uses more registers than the method already has
 * *assembles cleanly*, passes the Morphe build, and only explodes as a
 * `VerifyError` at dex-verify time on the device. This project has already been
 * bitten by exactly that class of bug once (the expansion-licensing hook passed
 * `p1` where the verifier expected `p0`; see
 * `notes/deployment-waydroid.md` §"Bug found and fixed during this deploy").
 *
 * Growing the frame is therefore done explicitly with
 * `app.morphe.util.cloneMutable(additionalRegisters = …)` + a remove/add on
 * `MutableClass.methods`, and this function proves afterwards that the swap
 * actually landed **and** that the resulting frame is what the injected smali
 * assumes. It reads from the class rather than from the local reference on
 * purpose: that is what catches a swap that silently did not take effect.
 *
 * @throws PatchException if the method is missing from [this] or its register
 * count differs from [expectedRegisters].
 */
internal fun MutableClass.requireRegisterCount(
    methodName: String,
    parameterTypes: List<String>,
    expectedRegisters: Int,
    patchName: String
): MutableMethod {
    val method = methods.firstOrNull {
        it.name == methodName && it.parameterTypes.map(CharSequence::toString) == parameterTypes
    } ?: throw PatchException(
        "$patchName: $methodName(${parameterTypes.joinToString("")}) is no longer present in" +
            " $type after the frame expansion — the method swap did not land."
    )

    val actual = method.implementation?.registerCount
    if (actual != expectedRegisters) {
        throw PatchException(
            "$patchName: $methodName(${parameterTypes.joinToString("")}) has .registers $actual," +
                " expected $expectedRegisters. The injected body addresses registers that do not" +
                " exist in that frame and would fail dex verification on device."
        )
    }

    return method
}

/**
 * Throws [PatchException] unless [this] contains exactly one match — i.e. the
 * MAX method moved, was renamed, or the SDK changed shape.
 *
 * Morphe resolves a fingerprint to zero matches silently, and a patch that ships
 * with one of its hooks unresolved still builds and still installs. For the ad
 * patches that failure mode is actively harmful — an unresolved `showInterstitial`
 * means interstitials keep playing while the patch claims to have removed them —
 * so it is turned into a hard failure instead.
 */
internal fun List<Match>?.requireExactlyOne(target: String, patchName: String) {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "$patchName: $target matched $count time(s), expected exactly 1. The AppLovin" +
                " MAX Unity facade has changed shape — refusing to emit an APK whose ad" +
                " behaviour is only half patched."
        )
    }
}

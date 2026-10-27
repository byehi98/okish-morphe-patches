package app.jetpackjoyride.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Halfbrick's LevelPlay ("mortar") bridge. Everything in this file lives in
 * `classes6.dex` of Jetpack Joyride 1.104.2 and keeps its **original,
 * non-obfuscated** Java names — the glue layer is not run through R8.
 */
internal const val PROVIDER = "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;"

internal const val REWARDED_LISTENER =
    "Lcom/halfbrick/mortar/ironsource/RewardedAd\$Listener;"

internal const val AD_UNIT_LISTENER =
    "Lcom/halfbrick/mortar/ironsource/IAdUnitListener;"

internal const val INTERSTITIAL_AD =
    "Lcom/halfbrick/mortar/ironsource/InterstitialAd;"

/**
 * `Provider_IronsourceMediationBackend.ShowRewardedAd(String)V` —
 * `classes6/com/halfbrick/mortar/Provider_IronsourceMediationBackend.smali:424`.
 *
 * ```
 * .method public static ShowRewardedAd(Ljava/lang/String;)V
 *     .registers 2
 *     new-instance v0, ...Provider_IronsourceMediationBackend$$ExternalSyntheticLambda7
 *     invoke-direct {v0, p0}, ...$$ExternalSyntheticLambda7;-><init>(Ljava/lang/String;)V
 *     invoke-static {v0}, ...;->runOnUiThread(Ljava/lang/Runnable;)V
 *     return-void
 * .end method
 * ```
 *
 * ## Why the obfuscated-looking name is actually stable
 *
 * This is **not** an obfuscated name: it is pinned by *native JNI registration*.
 * `libmortargame.so` (all four splits) registers
 * `com/halfbrick/mortar/Provider_IronsourceMediationBackend` +
 * `ShowRewardedAd` for `Provider_IronsourceMediationBackend_Java::ShowRewardedAd`
 * and upcalls it. Renaming the Java method would silently break the game's own
 * ad button, so Halfbrick cannot rename it without shipping a matching native
 * library. The method has **zero Java callers** (`rg 'ShowRewardedAd'` →
 * only the JNI-visible declaration and its lambda), which is the second proof
 * that the entry point is native-owned.
 *
 * ## Why there is no string/lambda filter
 *
 * The body contains no string literal at all — only a `new-instance` of a D8
 * synthetic lambda and a `runOnUiThread` call. The lambda **number** is
 * deliberately *not* used as a filter: `$$ExternalSyntheticLambda7` is renumbered
 * by D8 on every code change, and its sibling `ShowInterstitialAd(String)V`
 * (`.smali:411`) is byte-for-byte shape-identical except that it instantiates
 * `Lambda6`. Anchoring on the lambda number would therefore be both brittle
 * *and* the only thing separating the two methods. The name pin does that job
 * exactly and cannot drift while the JNI registration stays.
 *
 * The one instruction-level anchor kept is the `runOnUiThread(Runnable)V` call
 * into [PROVIDER] itself — cheap proof that the body is still the native-callback
 * hop and not something that was rewritten upstream.
 *
 * `.catch`: the whole file contains **zero** `.catch` directives, so the injected
 * early `return-void` cannot leave a try range dangling.
 */
object ShowRewardedAdFingerprint : Fingerprint(
    definingClass = PROVIDER,
    name = "ShowRewardedAd",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        methodCall(
            definingClass = PROVIDER,
            name = "runOnUiThread",
            parameters = listOf("Ljava/lang/Runnable;"),
            returnType = "V"
        )
    )
)

/**
 * `Provider_IronsourceMediationBackend.LoadRewardedAd()V` —
 * `classes6/com/halfbrick/mortar/Provider_IronsourceMediationBackend.smali:314`.
 *
 * ```
 * .method public static LoadRewardedAd()V
 *     .registers 1                                    ← only v0 exists
 *     new-instance v0, ...$$ExternalSyntheticLambda4
 *     invoke-direct {v0}, ...$$ExternalSyntheticLambda4;-><init>()V
 *     invoke-static {v0}, ...;->runOnUiThread(Ljava/lang/Runnable;)V
 *     return-void
 * .end method
 * ```
 *
 * Like [ShowRewardedAdFingerprint] the name is pinned by native JNI registration
 * (`Provider_IronsourceMediationBackend_Java::LoadRewardedAd`), so it survives
 * app updates. Zero parameters + `runOnUiThread` is the whole shape.
 *
 * The sibling `LoadInterstitialAd()V` (`.smali:300`) is identical apart from the
 * `new-instance` type (`…Lambda1` vs `…Lambda4`) and is *not* touched by either
 * patch — the interstitial loader is intentionally left loading normally so that
 * "Remove Ads" only intercepts the show call.
 *
 * `.catch`: none in the file.
 */
object LoadRewardedAdFingerprint : Fingerprint(
    definingClass = PROVIDER,
    name = "LoadRewardedAd",
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        methodCall(
            definingClass = PROVIDER,
            name = "runOnUiThread",
            parameters = listOf("Ljava/lang/Runnable;"),
            returnType = "V"
        )
    )
)

/**
 * `com.halfbrick.mortar.ironsource.InterstitialAd.ShowAd(String)Z` —
 * `classes6/com/halfbrick/mortar/ironsource/InterstitialAd.smali:284`.
 *
 * The single place in the whole app that hands an interstitial to LevelPlay:
 *
 * ```
 * AdUnitCollection.ShowAd(String)                      .smali:523  (shared dispatcher)
 *   └─ InterstitialAd.ShowAd(String)                   .smali:284  ← this method
 *        ├─ if (!IsReady())  → OnAdClosed(false); return false     (:317-322)
 *        └─ LevelPlayInterstitialAd.showAd(activity, placement)    (:345)
 * ```
 *
 * Two independent anchors, both verbatim from the shipped bytecode:
 *  * the log literal `"LevelPlay: Showing interstitial: {0} at {1}"` (`:336`), which
 *    exists nowhere else in the 9 dex files;
 *  * the `LevelPlayInterstitialAd.showAd(Activity,String)V` call (`:345`).
 *
 * The only other method in the app that calls `showAd(Activity,String)` is
 * `RewardedAd.ShowAd` (`RewardedAd.smali:274`) — excluded twice over, by the
 * `LevelPlayInterstitialAd` defining class inside the filter and by the string
 * literal. `definingClass` is pinned as well, so the interstitial and rewarded
 * paths can never be confused by the resolver.
 *
 * `.catch`: zero `.catch` directives in `InterstitialAd.smali`.
 */
object InterstitialAdShowAdFingerprint : Fingerprint(
    definingClass = INTERSTITIAL_AD,
    name = "ShowAd",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        string("LevelPlay: Showing interstitial: {0} at {1}"),
        methodCall(
            definingClass = "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;",
            name = "showAd",
            parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
            returnType = "V"
        )
    )
)

/**
 * Re-reads a method **out of its class** after a frame expansion and fails the
 * patch if the frame is not exactly [expectedRegisters] words.
 *
 * `addInstructions(0, …)` assembles the smali against whatever `registerCount`
 * the method already has, and `MutableMethodImplementation.registerCount` is a
 * `final` field of the dexlib2 builder — a body that addresses a register the
 * frame does not contain therefore compiles, passes `./gradlew buildAndroid`,
 * and only explodes as a `VerifyError` on the device. Growing the frame is done
 * with `cloneMutable(additionalRegisters = …)` + a remove/add on
 * `MutableClass.methods`; this function proves afterwards that the swap landed
 * and that the resulting frame matches what the injected smali assumes. It
 * reads from the class rather than from the local reference on purpose: that is
 * what catches a swap that silently did not take effect.
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
 * Throws [PatchException] unless [this] contains exactly one match.
 *
 * Morphe resolves a fingerprint to zero matches silently and a patch that ships
 * with an unresolved hook still builds and still installs — for an ad patch that
 * failure mode is actively harmful (the offer keeps playing an advert while the
 * patch claims it was removed), so it is turned into a hard failure instead.
 */
internal fun List<Match>?.requireExactlyOne(target: String, patchName: String) {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "$patchName: $target matched $count time(s), expected exactly 1. The Halfbrick" +
                " LevelPlay bridge has changed shape — refusing to emit an APK whose ad" +
                " behaviour is only half patched."
        )
    }
}

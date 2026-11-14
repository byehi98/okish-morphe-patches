package app.vitamahjong.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Vita Mahjong 3.47.0 — MEeVii ad SDK + Learnings bridge.
//
//   analysis/vita/…/classes6.dex = MEeVii SDK, R8-obfuscated
//   analysis/vita/…/classes8.dex = Learnings Unity bridge, readable
//
// `Lvd/a`, `Lsd/a`, `Lqd/a` and `Lkd/o` are R8-obfuscated MEeVii descriptors.
// Their short names are therefore version-pinned to *this* SDK build, which is
// why every fingerprint below is anchored on structure — the ordered call
// sequence or an exact diagnostic string — and not on the name alone. The name
// / descriptor is only the last, cheapest discriminator.
//
//   classes6/vd/a.smali   rewarded     show()Lyd/i;
//   classes6/sd/a.smali   interstitial show()Lyd/i;
//   classes6/kd/o.smali   o(String,String,String,Lkd/e;)Z   isReady
//   classes6/kd/o.smali   p(String)Z                         isValid
//   classes8/com/learnings/unity/unikit/module/AdModuleBridge.smali
//                         showBanner(String,int,int,String,boolean,String)V
// ============================================================================

/**
 * `Lvd/a.show()Lyd/i;` — rewarded-video display (`classes6`).
 *
 * Shipped body (`.registers 5`, `p0 = v4`) calls, in this exact order:
 *
 * ```
 * q()V → i()Lyd/i; → e(Lyd/i;)V → l(Lyd/i;)V → r(CosmosAdapter,Lyd/i;)Lyd/i;
 * ```
 *
 * The five-call sequence is the stable structural marker. `q`, `i`, `e` and `l`
 * are declared on the `Lqd/a` base; `r` is the abstract template hook each
 * concrete format overrides, so it is resolved on `Lvd/a` itself. Both facts
 * were read off the shipped smali and are encoded in the filters below.
 *
 * `accessFlags` is deliberately omitted: the obfuscated helper names on
 * `Lqd/a` are reshuffled between SDK builds (3.44.1 `p/h/d/k/q` →
 * 3.47.0 `q/i/e/l/r`), but the ordered call chain and the concrete `r()`
 * override are stable.
 *
 * `.catch`: the method owns one `.catchall {:try_start_1a .. :try_end_21}
 * :catchall_22` around `l()` + `r()`. The new implementation is built as a
 * fresh `MutableMethodImplementation`, so that handler is dropped together with
 * the body it belonged to (see `VitaMahjongAdsPatches.kt`).
 */
object RewardedShowFingerprint : Fingerprint(
    definingClass = "Lvd/a;",
    name = "show",
    returnType = "Lyd/i;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lqd/a;", name = "q"),
        methodCall(definingClass = "Lqd/a;", name = "i"),
        methodCall(definingClass = "Lqd/a;", name = "e"),
        methodCall(definingClass = "Lqd/a;", name = "l"),
        methodCall(definingClass = "Lvd/a;", name = "r"),
    )
)

/**
 * `Lsd/a.show()Lyd/i;` — interstitial display (`classes6`).
 *
 * Byte-for-byte the same shape as [RewardedShowFingerprint] apart from the
 * implementation class that owns the final `r(...)` override:
 *
 * ```
 * q()V → i()Lyd/i; → e(Lyd/i;)V → l(Lyd/i;)V → Lsd/a.r(...)Lyd/i;
 * ```
 *
 * Same single `.catchall` around `l()` + `r()`, same `.registers 5`.
 */
object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lsd/a;",
    name = "show",
    returnType = "Lyd/i;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lqd/a;", name = "q"),
        methodCall(definingClass = "Lqd/a;", name = "i"),
        methodCall(definingClass = "Lqd/a;", name = "e"),
        methodCall(definingClass = "Lqd/a;", name = "l"),
        methodCall(definingClass = "Lsd/a;", name = "r"),
    )
)

/**
 * `AdModuleBridge.showBanner(String,int,int,String,boolean,String)V`
 * (`classes8`).
 *
 * Called reflectively from Unity C# by exact public name + signature, so the
 * descriptor is stable. Shipped body (`.registers 13`) builds the banner
 * Runnable `Lcom/learnings/unity/unikit/module/n;` and hands it to
 * `UnityUtil.runOnUiThread`. The `runOnUiThread` call is the only non-obfuscated
 * anchor in the body and is used as the structural filter.
 *
 * `.catch`: none — a straight-line `new-instance` / `invoke-direct` /
 * `invoke-static` / `return-void`, which is why `returnEarly()` at index 0 is
 * safe here.
 */
object ShowBannerFingerprint : Fingerprint(
    definingClass = "Lcom/learnings/unity/unikit/module/AdModuleBridge;",
    name = "showBanner",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "I",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/learnings/unity/unikit/util/UnityUtil;",
            name = "runOnUiThread",
        )
    )
)

/**
 * MEeVii `isReady` gate — 3.47.0 moved it to `Lkd/o.o(String,String,String,Lkd/e;)Z`
 * (`classes6`), but the anchors are unchanged in spirit:
 *
 * Package-private, so the name alone is not usable as an anchor. It is uniquely
 * identified by its own diagnostic string followed by the mixer lookup
 * `Lmd/b;->a(Lkd/e;)Lyd/i;` (3.44.1 used `Lmd/b;->isReady()`; the SDK build
 * renamed that helper to `a`):
 *
 * ```
 * const-string p3, "isReady error not find config for placementId  : "
 * ...
 * invoke-virtual {p1, p4}, Lmd/b;->a(Lkd/e;)Lyd/i;
 * ```
 *
 * Two spaces before the colon — verbatim, byte-for-byte, from
 * `kd/o.smali`.
 */
object AdReadyFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lkd/e;",
    ),
    filters = listOf(
        string("isReady error not find config for placementId  : "),
        methodCall(definingClass = "Lmd/b;", name = "a"),
    )
)

/**
 * MEeVii `isValid` gate — `Lkd/o.p(String)Z` (`classes6`).
 *
 * Package-private, uniquely identified by:
 *
 * ```
 * const-string v2, "isValid error not find config for placementId  : "
 * ...
 * invoke-virtual {p1}, Lmd/b;->isValid()Lyd/i;
 * ```
 *
 * Two spaces before the colon, same as [AdReadyFingerprint].
 */
object AdValidFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("isValid error not find config for placementId  : "),
        methodCall(definingClass = "Lmd/b;", name = "isValid"),
    )
)

/**
 * Throws [PatchException] unless [this] holds exactly one match.
 *
 * Morphe resolves a fingerprint to zero matches silently, and a patch that ships
 * with an unresolved hook still builds and still installs. For an ad patch that
 * failure mode is actively harmful — the offer keeps playing an advert while the
 * patch claims it was removed — so it is turned into a hard failure instead.
 */
internal fun List<Match>?.requireExactlyOne(target: String, patchName: String) {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "$patchName: $target matched $count time(s), expected exactly 1. The MEeVii ad" +
                " SDK / Learnings bridge has changed shape — refusing to emit an APK whose ad" +
                " behaviour is only half patched."
        )
    }
}

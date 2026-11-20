package app.vitamahjong.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Vita Mahjong 3.48.0 — MEeVii ad SDK + Learnings bridge.
//
//   analysis/vitamahjong_348/dex/classes6.dex = MEeVii SDK, R8-obfuscated
//   analysis/vitamahjong_348/dex/classes8.dex = Learnings Unity bridge, readable
//
// `Lte/a`, `Lve/a`, `Lye/a`, `Lne/o` and `Lpe/b` are R8-obfuscated MEeVii
// descriptors. Their short names are therefore version-pinned to *this* SDK
// build, which is why every fingerprint below is anchored on structure — the
// ordered call sequence or an exact diagnostic string — and not on the name
// alone. The name / descriptor is only the last, cheapest discriminator.
//
// 3.48.0 reshuffle (3.47.0 → 3.48.0):
//
//   classes6/te/a.smali   BaseLoadStrategy   was Lqd/a  (q/i/e/l/h + field c)
//   classes6/ye/a.smali   RewardLoadApi      was Lvd/a  show()Lbf/i;
//   classes6/ve/a.smali   InterLoadApi       was Lsd/a  show()Lbf/i;
//   classes6/ne/o.smali   MeeviiMixer        was Lkd/o  o(...)Z / p(String)Z
//   classes6/pe/b.smali   load strategy      was Lmd/b  a(Lne/e;)Lbf/i; / isValid()
//
// The ad session handle type also moved: `Lyd/i;` → `Lbf/i;`.
//
//   classes8/com/learnings/unity/unikit/module/AdModuleBridge.smali
//                         showBanner(String,int,int,String,boolean,String)V
// ============================================================================

/**
 * `Lye/a.show()Lbf/i;` — rewarded-video display (`classes6`).
 *
 * Shipped body (`.registers 5`, `p0 = v4`) calls, in this exact order:
 *
 * ```
 * q()V → i()Lbf/i; → e(Lbf/i;)V → l(Lbf/i;)V → r(CosmosAdapter,Lbf/i;)Lbf/i;
 * ```
 *
 * The five-call sequence is the stable structural marker. `q`, `i`, `e` and `l`
 * are declared on the `Lte/a` base; `r` is the abstract template hook each
 * concrete format overrides, so it is resolved on `Lye/a` itself. Both facts
 * were read off the shipped smali and are encoded in the filters below.
 *
 * `accessFlags` is deliberately omitted: the obfuscated helper names on
 * `Lte/a` are reshuffled between SDK builds (3.44.1 `p/h/d/k/q` →
 * 3.47.0 `q/i/e/l/r` → 3.48.0 `q/i/e/l/r`), but the ordered call chain and the
 * concrete `r()` override are stable.
 *
 * `.catch`: the method owns one `.catchall {:try_start_1a .. :try_end_21}
 * :catchall_22` around `l()` + `r()`. The new implementation is built as a
 * fresh `MutableMethodImplementation`, so that handler is dropped together with
 * the body it belonged to (see `VitaMahjongAdsPatches.kt`).
 */
object RewardedShowFingerprint : Fingerprint(
    definingClass = "Lye/a;",
    name = "show",
    returnType = "Lbf/i;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lte/a;", name = "q"),
        methodCall(definingClass = "Lte/a;", name = "i"),
        methodCall(definingClass = "Lte/a;", name = "e"),
        methodCall(definingClass = "Lte/a;", name = "l"),
        methodCall(definingClass = "Lye/a;", name = "r"),
    )
)

/**
 * `Lve/a.show()Lbf/i;` — interstitial display (`classes6`).
 *
 * Byte-for-byte the same shape as [RewardedShowFingerprint] apart from the
 * implementation class that owns the final `r(...)` override:
 *
 * ```
 * q()V → i()Lbf/i; → e(Lbf/i;)V → l(Lbf/i;)V → Lve/a.r(...)Lbf/i;
 * ```
 *
 * Same single `.catchall` around `l()` + `r()`, same `.registers 5`.
 */
object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lve/a;",
    name = "show",
    returnType = "Lbf/i;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lte/a;", name = "q"),
        methodCall(definingClass = "Lte/a;", name = "i"),
        methodCall(definingClass = "Lte/a;", name = "e"),
        methodCall(definingClass = "Lte/a;", name = "l"),
        methodCall(definingClass = "Lve/a;", name = "r"),
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
 * MEeVii `isReady` gate — 3.48.0 keeps it at `Lne/o.o(String,String,String,Lne/e;)Z`
 * (`classes6`, 3.47.0 `Lkd/o`), with the anchors unchanged in spirit:
 *
 * Package-private, so the name alone is not usable as an anchor. It is uniquely
 * identified by its own diagnostic string followed by the load-strategy lookup
 * `Lpe/b;->a(Lne/e;)Lbf/i;` (3.44.1 used `Lmd/b;->isReady()`; 3.47.0 renamed
 * that helper to `a` on `Lmd/b`; 3.48.0 moved the class to `Lpe/b`):
 *
 * ```
 * const-string p3, "isReady error not find config for placementId  : "
 * ...
 * invoke-virtual {p1, p4}, Lpe/b;->a(Lne/e;)Lbf/i;
 * ```
 *
 * Two spaces before the colon — verbatim, byte-for-byte, from
 * `ne/o.smali`.
 */
object AdReadyFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lne/e;",
    ),
    filters = listOf(
        string("isReady error not find config for placementId  : "),
        methodCall(definingClass = "Lpe/b;", name = "a"),
    )
)

/**
 * MEeVii `isValid` gate — `Lne/o.p(String)Z` (`classes6`, 3.47.0 `Lkd/o`).
 *
 * Package-private, uniquely identified by:
 *
 * ```
 * const-string v2, "isValid error not find config for placementId  : "
 * ...
 * invoke-virtual {p1}, Lpe/b;->isValid()Lbf/i;
 * ```
 *
 * Two spaces before the colon, same as [AdReadyFingerprint].
 */
object AdValidFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("isValid error not find config for placementId  : "),
        methodCall(definingClass = "Lpe/b;", name = "isValid"),
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

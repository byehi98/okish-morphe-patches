package app.vitamahjong.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ============================================================================
// Vita Mahjong 3.42.1 — MEeVii ad SDK + Learnings Unity bridge.
//
// `Lvd/a` / `Lsd/a` / `Lqd/a` / `Lkd/n` are R8-obfuscated MEeVii descriptors
// (JADX renames them RewardLoadApi / InterLoadApi / BaseLoadStrategy /
// MeeviiMixer). They are version-pinned to this MEeVii SDK build, so every
// fingerprint below is anchored on structure (call sequence, exact log
// strings) rather than on the short method names alone.
//
// All bodies were read from smali before writing:
//   smali/classes6/vd/a.smali:192   rewarded show()
//   smali/classes6/sd/a.smali:166   interstitial show()
//   smali/classes6/qd/a.smali:335   g()Lyd/r placement id
//   smali/classes6/qd/a.smali:377   h()Lyd/i
//   smali/classes6/kd/n.smali:1901  o(String,String,String)Z  isReady
//   smali/classes6/kd/n.smali:2027  p(String)Z                isValid
//   smali/classes8/.../AdModuleBridge.smali:1835 showBanner(...)V
// ============================================================================

/**
 * `Lvd/a.show()Lyd/i;` — rewarded video display (`classes6`).
 *
 * Shipped body (`.registers 5`, p0=v4) calls, in order:
 *   p()V → h()Lyd/i; → d(Lyd/i;)V → k(Lyd/i;)V → q(CosmosAdapter,Lyd/i;)Lyd/i;
 *
 * The five-call sequence is the stable structural marker; `vd`, `qd` and the
 * single-letter names are obfuscated and will change when MEeVii updates.
 * `Lvd/a;->q(...)` is declared in `Lvd/a` (abstract), while p/h/d/k are on the
 * `Lqd/a` base — verified in smali.
 */
object RewardedShowFingerprint : Fingerprint(
    definingClass = "Lvd/a;",
    name = "show",
    returnType = "Lyd/i;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lqd/a;", name = "p"),
        methodCall(definingClass = "Lqd/a;", name = "h"),
        methodCall(definingClass = "Lqd/a;", name = "d"),
        methodCall(definingClass = "Lqd/a;", name = "k"),
        methodCall(definingClass = "Lvd/a;", name = "q"),
    )
)

/**
 * `Lsd/a.show()Lyd/i;` — interstitial display (`classes6`).
 *
 * Identical shape to the rewarded API except for the implementation class:
 *   p()V → h()Lyd/i; → d(Lyd/i;)V → k(Lyd/i;)V → Lsd/a.q(...)Lyd/i;
 */
object InterstitialShowFingerprint : Fingerprint(
    definingClass = "Lsd/a;",
    name = "show",
    returnType = "Lyd/i;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lqd/a;", name = "p"),
        methodCall(definingClass = "Lqd/a;", name = "h"),
        methodCall(definingClass = "Lqd/a;", name = "d"),
        methodCall(definingClass = "Lqd/a;", name = "k"),
        methodCall(definingClass = "Lsd/a;", name = "q"),
    )
)

/**
 * `AdModuleBridge.showBanner(String,int,int,String,boolean,String)V`
 * (`classes8`).
 *
 * `showBanner` is called reflectively by Unity, so the exact public name and
 * signature are stable. Shipped body (`.registers 13`) constructs the banner
 * Runnable `Lcom/learnings/unity/unikit/module/n;` and posts it through
 * `UnityUtil.runOnUiThread`. `runOnUiThread` is the one stable, non-obfuscated
 * call in the body and is used as the structural filter.
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
 * `Lkd/n.o(String,String,String)Z` — MEeVii `isReady` (`classes6`).
 *
 * Package-private, no stable name. It is uniquely identified by its exact
 * diagnostic string and the `Lmd/b;->isReady()` call that follows it:
 *
 *   const-string p3, "isReady error not find config for placementId  : "
 *   ...
 *   invoke-virtual {p1}, Lmd/b;->isReady()Lyd/i;
 *
 * (two spaces before the colon, verified byte-for-byte). No `accessFlags` on
 * purpose: the method is package-private (flag int 0), and the exact string is
 * the strong discriminator.
 */
object AdReadyFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        string("isReady error not find config for placementId  : "),
        methodCall(definingClass = "Lmd/b;", name = "isReady"),
    )
)

/**
 * `Lkd/n.p(String)Z` — MEeVii `isValid` (`classes6`).
 *
 * Package-private, uniquely identified by:
 *
 *   const-string v2, "isValid error not find config for placementId  : "
 *   ...
 *   invoke-virtual {p1}, Lmd/b;->isValid()Lyd/i;
 */
object AdValidFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("isValid error not find config for placementId  : "),
        methodCall(definingClass = "Lmd/b;", name = "isValid"),
    )
)

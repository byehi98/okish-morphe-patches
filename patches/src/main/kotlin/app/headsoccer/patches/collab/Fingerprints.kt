package app.headsoccer.patches.collab

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Head Soccer 7.1.12 (versionCode 358) — cross-promo install checks ───────
//
// Verified against the shipped smali:
//   analysis/head-soccer/smali/classes1/com/dnddream/headsoccer/android/headsoccer.smali
//   analysis/head-soccer/notes/collab-characters.md
//
// These three `public static (I)Z` probes are the ONLY Java code that gates the
// "install our other game to unlock this character" content. They have no Java
// caller at all — libgame.so looks them up by name through JNI:
//
//   Popup::clickUnlock  →  MenuLayer::DownLoad<X>(idx)
//     → GetStaticMethodInfo("Check<X>", "(I)Z")  →  CallStaticBooleanMethod(cls, mid, 0)
//       true  →  SelectLayer::UnlockCharacter(idx)   (grant + SavePlayerUnlock)
//       false →  MenuLayer::MoreGame("com.dnddream.<x>")   (Play Store)
//
// Each body is a bare `PackageManager.getLaunchIntentForPackage(<pkg>) != null`
// probe, so pinning Google's `getLaunchIntentForPackage` plus the exact package
// literal narrows the search to this probe family. `string()` is exact-match and
// each literal appears in exactly TWO methods in the whole DEX: the static check
// pinned here and its dead legacy instance twin (`CheckBoxing(I)I` /
// `CheckTank(I)I` / `CheckBasketball(I)I`, never looked up by libgame.so — none of
// those names exist in the native string table). The method name + `(I)Z`
// signature disambiguate. If a future build renames the app-private method the
// fingerprint fails loudly and writes nothing, instead of silently matching the
// wrong probe. (The Play Store URLs for these games live in libgame.so, not in Java.)

/**
 * `headsoccer.CheckHeadBoxing(I)Z` — smali line 623, `.registers 3`.
 *
 * Probes `com.dnddream.headboxing` (Head Boxing) and, as a legacy fallback for
 * pre-API-16 devices, returns true when `m_iSDKVersion < 16`. The `int` argument
 * is ignored — native always passes `0`.
 *
 * Consumed by `MenuLayer::DownLoadHeadBoxing(int)` (`0x84b728`), which grants the
 * character through `SelectLayer::UnlockCharacter(idx)` on true and opens the Play
 * Store listing on false. Also read by `MenuLayer::LoadImage(int)` to decide
 * whether to auto-show the "Head Boxing" event banner.
 */
object CheckHeadBoxingFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("I"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    definingClass = "Lcom/dnddream/headsoccer/android/headsoccer;",
    name = "CheckHeadBoxing",
    filters = listOf(
        // Order matters: Morphe ordered filters must follow the shipped instruction order.
        // `const-string <pkg>` is emitted BEFORE the getLaunchIntentForPackage invoke.
        string("com.dnddream.headboxing"),
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getLaunchIntentForPackage"
        ),
    )
)

/**
 * `headsoccer.CheckBoomTank(I)Z` — smali line 448, `.registers 3`.
 *
 * Probes `com.dnddream.battletank` (Boom Tank Showdown), same shape as
 * [CheckHeadBoxingFingerprint] including the `m_iSDKVersion < 16` legacy fallback.
 *
 * Consumed by `MenuLayer::DownLoadBoomTank(int)` (`0x84b808`) → grant via
 * `SelectLayer::UnlockCharacter(idx)` on true, Play Store on false.
 */
object CheckBoomTankFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("I"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    definingClass = "Lcom/dnddream/headsoccer/android/headsoccer;",
    name = "CheckBoomTank",
    filters = listOf(
        // Order matters: Morphe ordered filters must follow the shipped instruction order.
        // `const-string <pkg>` is emitted BEFORE the getLaunchIntentForPackage invoke.
        string("com.dnddream.battletank"),
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getLaunchIntentForPackage"
        ),
    )
)

/**
 * `headsoccer.CheckHeadBasketball(I)Z` — smali line 595, `.registers 2`.
 *
 * Probes `com.dnddream.HeadBasketball` (Head Basketball) with no legacy fallback —
 * the whole body is the single probe. Same consumer chain:
 * `MenuLayer::DownLoadBasketBall(int)` (`0x84b648`) → `SelectLayer::UnlockCharacter(idx)`.
 *
 * Kept as its own fingerprint (not folded into the two above) so each character's
 * gate can be dropped independently if a future build removes one of the games
 * from the roster — the game does not rename these methods between versions.
 */
object CheckHeadBasketballFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("I"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    definingClass = "Lcom/dnddream/headsoccer/android/headsoccer;",
    name = "CheckHeadBasketball",
    filters = listOf(
        // Order matters: Morphe ordered filters must follow the shipped instruction order.
        // `const-string <pkg>` is emitted BEFORE the getLaunchIntentForPackage invoke.
        string("com.dnddream.HeadBasketball"),
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getLaunchIntentForPackage"
        ),
    )
)

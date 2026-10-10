package app.progressbar95.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Progressbar95 1.1110 — AppLovin MAX bridge fingerprints ───────────────────
//
// Lua only knows ads via `plugin.applovinMax`; Chartboost/Unity/Vungle/Facebook
// entries are MAX mediation adapters with no separate Lua path, so neutralising
// this bridge removes ALL ads (hunter T5).
//
// Verified against:
//   analysis/progressbar95/smali/classes6/plugin/applovinMax/LuaLoader$Show.smali
//   analysis/progressbar95/smali/classes6/plugin/applovinMax/LuaLoader$IsLoaded.smali

/**
 * T5a — `LuaLoader$Show.invoke(LuaState)I` (`.registers 19`, public).
 *
 * Sets `functionSignature "applovin.show( adType [, options] )"` — the sole
 * Lua→ads path for banner + interstitial + rewarded display.
 */
object ApplovinShowFingerprint : Fingerprint(
    definingClass = "Lplugin/applovinMax/LuaLoader\$Show;",
    name = "invoke",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/naef/jnlua/LuaState;"),
    filters = listOf(
        string("applovin.show( adType [, options] )"),
    ),
)

/**
 * T5b — `LuaLoader$IsLoaded.invoke(LuaState)I` (`.registers 9`, public).
 *
 * Sets `"applovinMax.isLoaded( adType )"`. Forcing it to 0 makes Lua treat ads
 * as unavailable and skip ad breaks gracefully.
 */
object ApplovinIsLoadedFingerprint : Fingerprint(
    definingClass = "Lplugin/applovinMax/LuaLoader\$IsLoaded;",
    name = "invoke",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/naef/jnlua/LuaState;"),
    filters = listOf(
        string("applovinMax.isLoaded( adType )"),
    ),
)

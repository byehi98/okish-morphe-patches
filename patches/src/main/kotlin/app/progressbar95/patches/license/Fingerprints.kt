package app.progressbar95.patches.license

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Progressbar95 1.1110 — Corona LVL licensing fingerprint ──────────────────
//
// Google LVL verdict → Lua `license` event. `dontAllow` is what a
// re-signed/patched APK hits; `allow` calls `setLastCheckedAppVersion()` +
// `callLuaCallback(true, …)`.
//
// Verified against:
//   analysis/progressbar95/smali/classes/CoronaProvider/licensing/google/LuaLoader$MyLicenseCheckerCallback.smali
// (`allow`/`dontAllow`, both `.registers 6`, public, single int param).

/**
 * T4 — `MyLicenseCheckerCallback.dontAllow(int)V`.
 *
 * `string("network")` occurs exactly once in this class (the retry-path
 * `callLuaCallback(false, …, "network")`), so it uniquely identifies the deny
 * path. The patch redirects it into `allow(p1)` instead.
 */
object LvlDontAllowFingerprint : Fingerprint(
    definingClass = "LCoronaProvider/licensing/google/LuaLoader\$MyLicenseCheckerCallback;",
    name = "dontAllow",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    filters = listOf(
        string("network"),
    ),
)

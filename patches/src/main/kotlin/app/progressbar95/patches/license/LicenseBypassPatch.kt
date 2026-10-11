package app.progressbar95.patches.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.progressbar95.patches.shared.Constants.COMPATIBILITY_PROGRESSBAR95

/**
 * Progressbar95 1.1110 · **License Bypass** (hunter T4).
 *
 * Corona LVL (`CHECK_LICENSE` + `CoronaProvider.licensing.google`): on a
 * re-signed/patched APK the Play license check answers `dontAllow`, which
 * forwards `licensed=false` to Lua. This patch prepends `dontAllow` with a
 * redirect into the sibling `allow(p1)` — the game's OWN allow path
 * (`setLastCheckedAppVersion()` + `callLuaCallback(true,
 * translateResponse(p1), false, "")`) — then returns, so the original deny
 * body becomes unreachable dead code.
 *
 * Zero register cost: the redirect only reuses the incoming params
 * (`p0` = this callback, `p1` = response code); no locals touched.
 * Hardening patch — LVL likely only gates the expansion-file flow, not
 * gameplay — so `default = true` is safe to keep on.
 */
@Suppress("unused")
val progressbar95LicenseBypassPatch = bytecodePatch(
    name = "License Fix",
    description = "Stops license checks from blocking the game on patched installs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PROGRESSBAR95)

    execute {
        // dontAllow → allow(p1): licensed=true via the game's own callback.
        LvlDontAllowFingerprint.method.addInstructions(0, """
            invoke-virtual {p0, p1}, LCoronaProvider/licensing/google/LuaLoader${'$'}MyLicenseCheckerCallback;->allow(I)V
            return-void
        """.trimIndent())
    }
}

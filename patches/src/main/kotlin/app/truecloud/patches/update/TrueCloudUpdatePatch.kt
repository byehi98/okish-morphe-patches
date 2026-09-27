package app.truecloud.patches.update

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.truecloud.patches.shared.Constants.COMPATIBILITY_TRUECLOUD

@Suppress("unused")
val trueCloudUpdatePatch = bytecodePatch(
    name = "TrueCloud Update",
    description = "Blocks all app update and force-update dialogs and their version checks.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        // T2 — server flag `upgrade == 1` gates every update dialog in the app:
        // login-screen forced install dialog (VersionDownloadHelper.checkForce),
        // Me-tab auto check (X35PersonalCentrePresenter), and the update notice
        // (VersionDownloadHelper.needShowDialog). Returning 0 kills all three paths
        // without touching the HTTP layer. Field names upgrade/force are Gson
        // round-tripped, so the POJO accessors stay un-obfuscated (verified).
        VersionInfoRespGetUpgradeFingerprint.method.returnEarly(0)

        // T13+T14 — belt-and-braces supplement to T2: stop the update checks before
        // any HTTP request is made (T2 only neutralizes the response flag).
        //   checkForce()          — login-screen forced update probe
        //   needShowDialog()      — Me-tab update notice gate
        //   checkVersionUpgrade() — Me-tab auto/manual version check
        // Redundant while the T2 choke above is enabled; kept because a manual
        // "check update" tap then silently does nothing.
        VersionDownloadHelperCheckForceFingerprint.method.returnEarly()
        VersionDownloadHelperNeedShowDialogFingerprint.method.returnEarly(false)
        CheckVersionUpgradeFingerprint.method.returnEarly()
    }
}

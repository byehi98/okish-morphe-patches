package app.moviebox.patches.update

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.moviebox.patches.shared.Constants.COMPATIBILITY_MOVIEBOX

private val returnFalse = """
    const/4 v0, 0x0
    return v0
""".trimIndent()

@Suppress("unused")
val movieBoxUpdateBypassPatch = bytecodePatch(
    name = "MovieBox Force Update Bypass",
    description = "Disables forced and nag update prompts in MovieBox.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MOVIEBOX)

    execute {
        // Never report a forced update — the app keeps working on any version.
        ForceUpdateFingerprint.method.addInstructions(0, returnFalse)
        // Never report an available update — suppresses the UpdateDialog
        // (both Tinker hot-patch and full APK install prompts).
        HasUpdateFingerprint.method.addInstructions(0, returnFalse)
    }
}

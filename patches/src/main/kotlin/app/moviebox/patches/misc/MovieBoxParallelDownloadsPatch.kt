package app.moviebox.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.moviebox.patches.shared.Constants.COMPATIBILITY_MOVIEBOX

@Suppress("unused")
val movieBoxParallelDownloadsPatch = bytecodePatch(
    name = "MovieBox Parallel Downloads",
    description = "Allows up to 5 simultaneous downloads in MovieBox.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MOVIEBOX)

    execute {
        ParallelDownloadLimitFingerprint.method.addInstructions(0, """
            const/4 v0, 0x5
            return v0
        """.trimIndent())
    }
}

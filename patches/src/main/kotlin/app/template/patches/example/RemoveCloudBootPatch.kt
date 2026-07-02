package app.template.patches.example

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECLOUD

@Suppress("unused")
val removeCloudBootPatch = bytecodePatch(
    name = "Remove Cloud Boot Page",
    description = "Removes the cloud storage ad and startup statistics page",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        CloudBootPageHelperFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """
        )
    }
}

package app.template.patches.example

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECLOUD

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Removes ads from TrueCloud",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        AdServiceObtainFingerprint.method.addInstructions(
            0,
            """
                const/4 p0, 0x0
                return-object p0
            """
        )

        AdServiceObtain3ArgFingerprint.method.addInstructions(
            0,
            """
                const/4 p0, 0x0
                return-object p0
            """
        )
    }
}

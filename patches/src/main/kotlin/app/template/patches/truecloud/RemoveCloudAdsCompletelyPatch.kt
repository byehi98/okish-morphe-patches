package app.template.patches.truecloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECLOUD
import com.android.tools.smali.dexlib2.AccessFlags

object CloudHelperIsSupport : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
    name = "isSupport",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/CloudHelper;" }
)

object LvCloudHelperIsSupport : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
    name = "isSupport",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/LvCloudHelper;" }
)

object VNCloudHelperIsSupport : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
    name = "isSupport",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/VNCloudHelper;" }
)

@Suppress("unused")
val removeCloudAdsCompletelyPatch = bytecodePatch(
    name = "Remove Cloud Ads Completely",
    description = "Completely disables Cloud API support, effectively removing all cloud storage ads across the entire application.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        val returnFalseInsns = """
            const/4 v0, 0x0
            return v0
        """.trimIndent()
        
        CloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        LvCloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        VNCloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
    }
}

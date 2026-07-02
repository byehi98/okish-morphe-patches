package app.template.patches.truecloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECLOUD
import com.android.tools.smali.dexlib2.AccessFlags

object FloatFragmentCloudUpgradePrompt : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    name = "showCloudUpgradePrompt",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DisplayFloatFragment;" }
)

object X35EventControlFastBuyCloud : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "showPlaybackFastBuyCloudTipsDialog",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35EventControlFragment;" }
)

object X35EventControl4LandFastBuyCloud : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "showPlaybackFastBuyCloudTipsDialog",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35EventControl4LandFragment;" }
)

object CloudEventDisplayBuyPrompt : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z", "Z"),
    name = "showCloudBuyPrompt",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/CloudEventDisplayFragment;" }
)

object CloudEventDisplayUpgradeTips : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.SYNTHETIC),
    returnType = "V",
    parameters = emptyList(),
    name = "showCloudUpgradeTips",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/CloudEventDisplayFragment;" }
)

object X35EventControlUpgradeTips : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    name = "showCloudUpgradeTips",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35EventControlFragment;" }
)

object X35EventControlUpgradeTipsFloat : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "showCloudUpgradeTipsOnFloat",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35EventControlFragment;" }
)

object EventControlUpgradeTips : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.SYNTHETIC),
    returnType = "V",
    parameters = emptyList(),
    name = "showCloudUpgradeTips",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/EventControlFragment;" }
)

@Suppress("unused")
val removeEventCloudPopupPatch = bytecodePatch(
    name = "Remove Event Cloud Popup",
    description = "Removes the upgrade cloud storage popup when opening the Event tab",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        val returnVoidInsns = """
            return-void
        """.trimIndent()
        
        FloatFragmentCloudUpgradePrompt.method.addInstructions(0, returnVoidInsns)
        X35EventControlFastBuyCloud.method.addInstructions(0, returnVoidInsns)
        X35EventControl4LandFastBuyCloud.method.addInstructions(0, returnVoidInsns)
        CloudEventDisplayBuyPrompt.method.addInstructions(0, returnVoidInsns)
        CloudEventDisplayUpgradeTips.method.addInstructions(0, returnVoidInsns)
        X35EventControlUpgradeTips.method.addInstructions(0, returnVoidInsns)
        X35EventControlUpgradeTipsFloat.method.addInstructions(0, returnVoidInsns)
        EventControlUpgradeTips.method.addInstructions(0, returnVoidInsns)
    }
}

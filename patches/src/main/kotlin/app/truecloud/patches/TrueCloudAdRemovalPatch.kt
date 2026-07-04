package app.truecloud.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.truecloud.patches.shared.Constants.COMPATIBILITY_TRUECLOUD
import com.android.tools.smali.dexlib2.AccessFlags

// === ADService Fingerprints ===
object AdServiceObtainFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/content/Context;", "Lcom/juanvision/bussiness/ad/ADTYPE;"),
    name = "obtain",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/ad/ADService;" }
)

object AdServiceObtain3ArgFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/content/Context;", "Lcom/juanvision/bussiness/ad/ADTYPE;", "Z"),
    name = "obtain",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/ad/ADService;" }
)

// === Cloud Boot Page Fingerprint ===
object CloudBootPageHelperFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/cloudBoot/CloudBootPageHelper\$InterceptData;",
    parameters = listOf("Landroid/content/Context;"),
    name = "shouldIntercept",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/cloudBoot/CloudBootPageHelper;" }
)

// === CloudAPI.isSupport Fingerprints ===
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

// === Help Center Robot Fingerprints ===
object X35MainListFragmentOnResume : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    name = "onResume",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;" }
)

object X35MainListFragmentStartHelpIconShowAnimation : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "startHelpIconShowAnimation",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;" }
)

@Suppress("unused")
val trueCloudAdRemovalPatch = bytecodePatch(
    name = "TrueCloud Ad Removal",
    description = "Consolidated patch to remove all ads, boot pages, cloud popups, and the help center robot.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        val returnNullInsns = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()

        val returnFalseInsns = """
            const/4 v0, 0x0
            return v0
        """.trimIndent()
        
        val returnVoidInsns = """
            return-void
        """.trimIndent()
        
        val hideRobotInstructions = """
            iget-object v0, p0, Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;->mBinding:Landroidx/viewbinding/ViewBinding;
            check-cast v0, Lcom/zasko/modulemain/databinding/FragmentX35MainListBinding;
            iget-object v0, v0, Lcom/zasko/modulemain/databinding/FragmentX35MainListBinding;->helpIv:Landroid/widget/ImageView;
            const/16 v1, 0x8
            invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setVisibility(I)V
        """.trimIndent()

        // 1. Disable ADService
        AdServiceObtainFingerprint.method.addInstructions(0, returnNullInsns)
        AdServiceObtain3ArgFingerprint.method.addInstructions(0, returnNullInsns)
        
        // 2. Disable Cloud Boot Page
        CloudBootPageHelperFingerprint.method.addInstructions(0, returnNullInsns)
        
        // 3. Completely Disable Cloud APIs to remove all cloud prompts
        CloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        LvCloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        VNCloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        
        // 4. Hide Help Center Robot
        X35MainListFragmentOnResume.method.addInstructions(0, hideRobotInstructions)
        X35MainListFragmentStartHelpIconShowAnimation.method.addInstructions(0, returnVoidInsns)
    }
}

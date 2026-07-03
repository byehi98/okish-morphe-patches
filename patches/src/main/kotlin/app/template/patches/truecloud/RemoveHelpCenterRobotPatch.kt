package app.template.patches.truecloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECLOUD
import com.android.tools.smali.dexlib2.AccessFlags

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
val removeHelpCenterRobotPatch = bytecodePatch(
    name = "Remove Help Center Robot",
    description = "Removes the floating robot customer service icon on the main screen.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        val hideInstructions = """
            iget-object v0, p0, Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;->mBinding:Landroidx/viewbinding/ViewBinding;
            check-cast v0, Lcom/zasko/modulemain/databinding/FragmentX35MainListBinding;
            iget-object v0, v0, Lcom/zasko/modulemain/databinding/FragmentX35MainListBinding;->helpIv:Landroid/widget/ImageView;
            const/16 v1, 0x8
            invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setVisibility(I)V
        """.trimIndent()
        
        X35MainListFragmentOnResume.method.addInstructions(0, hideInstructions)
        
        val returnVoidInsns = """
            return-void
        """.trimIndent()
        
        X35MainListFragmentStartHelpIconShowAnimation.method.addInstructions(0, returnVoidInsns)
    }
}

package app.template.patches.truecloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECLOUD
import com.android.tools.smali.dexlib2.AccessFlags

object X35DeviceListPresenterLoadBottomFloatAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Landroid/widget/FrameLayout;"),
    name = "loadBottomFloatAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/X35DeviceListPresenter;" }
)

object X35DeviceListPresenterLoadBottomSelfAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    name = "loadBottomSelfAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/X35DeviceListPresenter;" }
)

object X35DeviceListPresenterLoadNativeAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/app/Activity;"),
    name = "loadNativeAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/X35DeviceListPresenter;" }
)

object X35DeviceListPresenterLoadSelfAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    name = "loadSelfAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/X35DeviceListPresenter;" }
)

object X35DeviceListFragmentShowAdDialog : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    name = "showAdDialog",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentShowAdvertCustomizePrompt : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Lcom/juanvision/http/pojo/user/LoginUserInfo\$AdvUrlClass;", "Ljava/lang/String;"),
    name = "showAdvertCustomizePrompt",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentShowDeviceAdCloudResult : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;", "Z", "Lcom/juanvision/http/pojo/cloud/ActivityParticipateInfo;"),
    name = "showDeviceAdCloudResult",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentShowFreeTrialCloudAdDialog : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;"),
    name = "showFreeTrialCloudAdDialog",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentShowInterstitialAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "showInterstitialAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentShowNewDeviceAdCloudResult : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;", "Z", "Lcom/juanvision/http/pojo/cloud/GoodsInfo;"),
    name = "showNewDeviceAdCloudResult",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentTryToShowInterAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "tryToShowInterAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentTryToShowInterAdIntent : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;"),
    name = "tryToShowInterAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentTryToShowSplashAd : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "tryToShowSplashAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

object X35DeviceListFragmentTryToShownAdFreeDialog : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "tryToShownAdFreeDialog",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;" }
)

@Suppress("unused")
val removeDeviceListAdsPatch = bytecodePatch(
    name = "Remove Device List Ads",
    description = "Removes banner and interstitial ads from the device list screen.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        val returnVoidInsns = """
            return-void
        """.trimIndent()
        
        val returnNullInsns = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()
        
        // Presenter
        X35DeviceListPresenterLoadBottomFloatAd.method.addInstructions(0, returnVoidInsns)
        X35DeviceListPresenterLoadBottomSelfAd.method.addInstructions(0, returnVoidInsns)
        X35DeviceListPresenterLoadNativeAd.method.addInstructions(0, returnNullInsns)
        X35DeviceListPresenterLoadSelfAd.method.addInstructions(0, returnVoidInsns)
        
        // Fragment
        X35DeviceListFragmentShowAdDialog.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentShowAdvertCustomizePrompt.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentShowDeviceAdCloudResult.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentShowFreeTrialCloudAdDialog.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentShowInterstitialAd.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentShowNewDeviceAdCloudResult.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentTryToShowInterAd.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentTryToShowInterAdIntent.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentTryToShowSplashAd.method.addInstructions(0, returnVoidInsns)
        X35DeviceListFragmentTryToShownAdFreeDialog.method.addInstructions(0, returnVoidInsns)
    }
}

package app.template.patches.truecloud

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

object CloudBootPageHelperFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/cloudBoot/CloudBootPageHelper\$InterceptData;",
    parameters = listOf("Landroid/content/Context;"),
    name = "shouldIntercept",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/bussiness/cloudBoot/CloudBootPageHelper;"
    }
)

package app.template.patches.truecloud

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

object AdServiceObtainFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/content/Context;", "Lcom/juanvision/bussiness/ad/ADTYPE;"),
    name = "obtain",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/bussiness/ad/ADService;"
    }
)

object AdServiceObtain3ArgFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/content/Context;", "Lcom/juanvision/bussiness/ad/ADTYPE;", "Z"),
    name = "obtain",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/bussiness/ad/ADService;"
    }
)

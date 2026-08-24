package app.tentaclewars.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_TENTACLE_WARS = Compatibility(
        name = "Tentacle Wars",
        packageName = "com.fdgentertainment.tentaclewars.gp.free",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x00838F,
        targets = listOf(
            AppTarget(version = "2.1.27")
        )
    )
}

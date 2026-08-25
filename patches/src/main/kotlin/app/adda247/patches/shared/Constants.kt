package app.adda247.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_ADDA247 = Compatibility(
        name = "Adda247",
        packageName = "com.adda247.app",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x00A651,
        targets = listOf(
            AppTarget(version = "12.6.4")
        )
    )
}

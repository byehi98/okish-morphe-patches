package app.earntodie2.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_EARNTODIE2 = Compatibility(
        name = "Earn to Die 2",
        packageName = "com.notdoppler.earntodie2",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF6F00,
        targets = listOf(
            AppTarget(version = "1.4.58")
        )
    )
}

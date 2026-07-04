package app.truecloud.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_TRUECLOUD = Compatibility(
        name = "TrueCloud",
        packageName = "com.generalcomp.truecloud",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0045, // Icon color in Morphe Manager
        targets = listOf(
            AppTarget(
                version = "4.6.5.13"
            )
        )
    )
}

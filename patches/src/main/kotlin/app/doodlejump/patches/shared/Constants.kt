package app.doodlejump.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DOODLEJUMP = Compatibility(
        name = "Doodle Jump",
        packageName = "com.lima.doodlejump",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x66BB6A,
        targets = listOf(
            AppTarget(version = "3.11.38"),
            AppTarget(version = "3.11.40")
        )
    )
}

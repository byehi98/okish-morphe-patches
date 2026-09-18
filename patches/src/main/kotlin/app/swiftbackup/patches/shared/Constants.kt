package app.swiftbackup.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SWIFTBACKUP = Compatibility(
        name = "Swift Backup",
        packageName = "org.swiftapps.swiftbackup",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1976D2,
        targets = listOf(
            AppTarget(version = "5.1.0")
        )
    )
}

package app.smashit.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SMASHIT = Compatibility(
        name = "Smash Hit",
        packageName = "com.mediocre.smashhit",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1E88E5,
        targets = listOf(
            AppTarget(version = "1.5.14")
        )
    )
}

package app.moviebox.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_MOVIEBOX = Compatibility(
        name = "MovieBox",
        packageName = "com.community.oneroom",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE53935,
        targets = listOf(
            AppTarget(version = "4.0.01.0813.02")
        )
    )
}

package app.subwaysurfers.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SUBWAY_SURFERS = Compatibility(
        name = "Subway Surfers",
        packageName = "com.kiloo.subwaysurf",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFFC107,
        targets = listOf(
            AppTarget(version = "3.69.1")
        )
    )
}

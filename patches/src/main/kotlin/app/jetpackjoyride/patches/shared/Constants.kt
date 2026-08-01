package app.jetpackjoyride.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_JETPACKJOYRIDE = Compatibility(
        name = "Jetpack Joyride",
        packageName = "com.halfbrick.jetpackjoyride",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xF57F17,
        targets = listOf(
            AppTarget(version = "1.104.1")
        )
    )
}

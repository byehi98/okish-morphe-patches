package app.drdriving.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DRDRIVING = Compatibility(
        name = "Dr. Driving",
        packageName = "com.ansangha.drdriving",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x1565C0,
        targets = listOf(
            AppTarget(version = "1.73")
        )
    )
}

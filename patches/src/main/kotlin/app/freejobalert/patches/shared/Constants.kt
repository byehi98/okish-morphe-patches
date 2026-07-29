package app.freejobalert.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_FREEJOBALERT = Compatibility(
        name = "FreeJobAlert",
        packageName = "com.freejobalert",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x1565C0,
        targets = listOf(
            AppTarget(version = "1.0.0")
        )
    )
}

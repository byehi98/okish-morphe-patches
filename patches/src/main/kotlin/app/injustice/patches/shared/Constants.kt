package app.injustice.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_INJUSTICE = Compatibility(
        name = "Injustice: Gods Among Us",
        packageName = "com.wb.goog.injustice",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFFC107,
        targets = listOf(
            AppTarget(version = "3.5.1")
        )
    )
}
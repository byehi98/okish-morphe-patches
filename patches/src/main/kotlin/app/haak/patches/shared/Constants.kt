package app.haak.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_HAAK = Compatibility(
        name = "HAAK",
        packageName = "com.blingame.haaktestb",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x00BCD4,
        targets = listOf(
            AppTarget(version = "1.4.3")
        )
    )
}

package app.bighunter.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_BIG_HUNTER = Compatibility(
        name = "Big Hunter",
        packageName = "com.kakarod.bighunter",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x8B4513,
        targets = listOf(
            AppTarget(version = "3.1.1"),
        ),
    )
}

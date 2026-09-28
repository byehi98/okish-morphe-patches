package app.lumina.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_LUMINA = Compatibility(
        name = "Lumina Walls",
        packageName = "com.lumina.wallpapers",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x6200EE, // Icon color in Morphe Manager
        targets = listOf(
            AppTarget(version = "1.0.2.6")
        )
    )
}

package app.vector.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_VECTOR = Compatibility(
        name = "Vector",
        packageName = "com.nekki.vector",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x00E676,
        targets = listOf(
            AppTarget(version = "2.10.0")
        )
    )
}

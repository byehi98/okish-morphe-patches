package app.dantheaman.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DTM = Compatibility(
        name = "Dan the Man",
        packageName = "com.halfbrick.dantheman",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x1A237E,
        targets = listOf(
            AppTarget(version = "1.14.03")
        )
    )
}

package app.fancade.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_FANCADE = Compatibility(
        name = "Fancade",
        packageName = "com.martinmagni.fancade",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFFB300,
        targets = listOf(
            AppTarget(version = "1.15.24")
        )
    )
}

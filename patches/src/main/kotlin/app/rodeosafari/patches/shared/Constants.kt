package app.rodeosafari.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_RODEO = Compatibility(
        name = "Rodeo Stampede: Sky Zoo Safari",
        packageName = "com.yodo1.rodeo.safari",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x689F38,
        targets = listOf(
            AppTarget(version = "4.23.0"),
            AppTarget(version = "4.24.0")
        )
    )
}

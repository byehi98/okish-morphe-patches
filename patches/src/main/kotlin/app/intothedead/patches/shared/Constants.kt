package app.intothedead.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_INTO_THE_DEAD = Compatibility(
        name = "Into the Dead",
        packageName = "com.sidheinteractive.sif.DR",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x8B0000,
        targets = listOf(
            AppTarget(version = "2.9.3")
        )
    )
}

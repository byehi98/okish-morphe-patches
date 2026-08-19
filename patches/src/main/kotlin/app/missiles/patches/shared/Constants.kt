package app.missiles.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_MISSILES = Compatibility(
        name = "Missiles",
        packageName = "pl.macaque.Missiles",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x2196F3,
        targets = listOf(
            AppTarget(version = "1.41")
        )
    )
}

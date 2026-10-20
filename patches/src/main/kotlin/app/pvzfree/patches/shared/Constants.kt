package app.pvzfree.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_PVZFREE = Compatibility(
        name = "Plants vs. Zombies FREE",
        packageName = "com.ea.game.pvzfree_row",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x4CAF50,
        targets = listOf(
            AppTarget(version = "3.18.0")
        )
    )
}

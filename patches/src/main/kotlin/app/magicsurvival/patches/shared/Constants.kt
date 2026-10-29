package app.magicsurvival.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_MAGIC_SURVIVAL = Compatibility(
        name = "Magic Survival",
        packageName = "com.vkslrzm.Zombie",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x76B947,
        targets = listOf(
            AppTarget(version = "0.9941")
        )
    )
}

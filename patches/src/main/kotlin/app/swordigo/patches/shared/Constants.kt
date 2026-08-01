package app.swordigo.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SWORDIGO = Compatibility(
        name = "Swordigo",
        packageName = "com.touchfoo.swordigo",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xD32F2F,
        targets = listOf(
            AppTarget(version = "1.4.12")
        )
    )
}

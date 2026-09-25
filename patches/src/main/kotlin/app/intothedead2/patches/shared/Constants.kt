package app.intothedead2.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_INTO_THE_DEAD_2 = Compatibility(
        name = "Into the Dead 2",
        packageName = "com.pikpok.dr2.play",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x8B0000,
        targets = listOf(
            AppTarget(version = "1.87.1")
        )
    )
}

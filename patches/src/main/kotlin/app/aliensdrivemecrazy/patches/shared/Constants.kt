package app.aliensdrivemecrazy.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_ADMC = Compatibility(
        name = "Aliens Drive Me Crazy",
        packageName = "com.rebeltwins.aliensdrivemecrazy",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x7CB342,
        targets = listOf(
            AppTarget(version = "3.2.10"),
        ),
    )
}

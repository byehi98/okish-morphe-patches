package app.ubisoftpop.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_POP = Compatibility(
        name = "Prince of Persia: The Lost Crown",
        packageName = "com.ubisoft.princeofpersia.thelostcrown.mobile.action.adventure.platform",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x0F4C5C,
        targets = listOf(
            AppTarget(version = "1.1.9")
        )
    )
}

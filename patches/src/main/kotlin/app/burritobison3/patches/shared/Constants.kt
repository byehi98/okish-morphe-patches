package app.burritobison3.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_BURRITO_BISON = Compatibility(
        name = "Burrito Bison: Launcha Libre",
        packageName = "com.kongregate.mobile.burritobison3.google",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xE65100,
        targets = listOf(
            AppTarget(version = "3.75")
        )
    )
}

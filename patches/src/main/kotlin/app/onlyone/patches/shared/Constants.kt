package app.onlyone.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_ONLY_ONE = Compatibility(
        name = "Only One",
        packageName = "com.rebelbinary.onlyone",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xE2E2E2, // ic_launcher_background (#e2e2e2)
        targets = listOf(
            AppTarget(version = "1.2983")
        )
    )
}

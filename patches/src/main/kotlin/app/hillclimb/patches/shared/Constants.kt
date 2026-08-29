package app.hillclimb.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_HILLCLIMB = Compatibility(
        name = "Hill Climb Racing",
        packageName = "com.fingersoft.hillclimb",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x2E7D32,
        targets = listOf(
            AppTarget(version = "1.70.0"),
            AppTarget(version = "1.71.1")
        )
    )
}

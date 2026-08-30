package app.trafficrider.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_TRAFFICRIDER = Compatibility(
        name = "Traffic Rider",
        packageName = "com.skgames.trafficrider",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFF5722,
        targets = listOf(
            AppTarget(version = "2.11")
        )
    )
}

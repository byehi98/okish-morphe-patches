package app.crossyroad.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_CROSSY_ROAD = Compatibility(
        name = "Crossy Road",
        packageName = "com.yodo1.crossyroad",
        apkFileType = ApkFileType.XAPK,
        // Assumed brand color (grass-green) — recon notes have no icon color; adjust if the
        // patch UI wants the exact icon hue.
        appIconColor = 0x7CB342,
        targets = listOf(
            AppTarget(version = "7.13.0")
        )
    )
}

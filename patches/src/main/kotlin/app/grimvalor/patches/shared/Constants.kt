package app.grimvalor.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_GRIMVALOR = Compatibility(
        name = "Grimvalor",
        packageName = "com.direlight.grimvalor",
        // Play App Signing re-sign + 3 splits (base + UnityDataAssetPack + config.arm64_v8a)
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x8B0A1A, // dark vampire crimson
        targets = listOf(
            AppTarget(version = "1.2.13")
        )
    )
}
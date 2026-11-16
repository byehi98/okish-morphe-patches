package app.opheliasparadise.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_OPHELIAS_PARADISE = Compatibility(
        name = "Ophelia's Paradise",
        packageName = "com.artingames.opheliasparadise",
        // Split bundle (base + config.arm64_v8a, requiredSplitTypes="base__abi").
        apkFileType = ApkFileType.APKS,
        appIconColor = 0x2B1B3A, // twilight purple from the title art
        targets = listOf(
            AppTarget(version = "26.11.15")
        )
    )
}

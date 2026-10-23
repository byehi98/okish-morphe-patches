package app.minutestilldawn.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_20MTD = Compatibility(
        name = "20 Minutes Till Dawn",
        description = "20 Minutes Till Dawn — roguelike survival shooter (Flanne)",
        packageName = "com.Flanne.MinutesTillDawn.roguelike.shooting.fr.gp",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x1A1A2E,
        targets = listOf(
            // APKPure XAPK: base APK + config.arm64_v8a, versionCode 1093, Unity IL2CPP.
            AppTarget(version = "8.6"),
        )
    )
}

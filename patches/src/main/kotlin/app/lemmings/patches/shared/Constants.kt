package app.lemmings.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_LEMMINGS = Compatibility(
        name = "Lemmings: Puzzle Survival",
        packageName = "com.sadpuppy.lemmings",
        // APKPure XAPK: base APK + config.arm64_v8a / config.armeabi_v7a native
        // splits (base APK holds classes.dex–classes9.dex; morphe-cli resolves
        // the bundle itself).
        apkFileType = ApkFileType.XAPK,
        // Sampled from the XAPK's own icon.png: warm sunset-orange accent
        // (dominant icon tone, with a dark-navy secondary).
        appIconColor = 0xFFAD71,
        targets = listOf(
            AppTarget(version = "9.4.4"),
        ),
    )
}

package app.crazygames.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_CRAZYGAMES = Compatibility(
        name = "CrazyGames",
        packageName = "com.crazygames.crazygamesapp",
        // 1.7.14 ships split-only (no native libs in the base APK), so it must be
        // patched as a bundle. Use the APKCube .apks: it is the only 1.7.14 bundle
        // that carries BOTH ABI splits (split_config.arm64_v8a.apk and
        // split_config.armeabi_v7a.apk) plus all language/density splits. The
        // APKPure .xapk variant is arm64-only and is therefore not used. morphe-cli
        // accepts the .apks directly and merges the splits, so do not rebuild a
        // monolithic APK by hand — that would drop the native splits the
        // WebView/Capacitor runtime needs.
        apkFileType = ApkFileType.APKS,
        // Purple (#6842FF) sampled from the CrazyGames launcher icon.
        appIconColor = 0x6842FF,
        targets = listOf(
            AppTarget(version = "1.7.14")
        )
    )
}

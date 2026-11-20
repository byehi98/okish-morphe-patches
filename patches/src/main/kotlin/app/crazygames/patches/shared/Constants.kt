package app.crazygames.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_CRAZYGAMES = Compatibility(
        name = "CrazyGames",
        packageName = "com.crazygames.crazygamesapp",
        // 1.7.13 ships as an .xapk: a 34 MB base APK (com.crazygames.crazygamesapp.apk)
        // plus 57 config splits (languages, densities, and the two ABI splits
        // config.arm64_v8a.apk / config.armeabi_v7a.apk). morphe-cli accepts the
        // .xapk directly and merges the splits, so do not rebuild a monolithic
        // APK by hand — that would drop the native splits the WebView/Capacitor
        // runtime needs.
        apkFileType = ApkFileType.XAPK,
        // Purple (#6842FF) sampled from the CrazyGames launcher icon.
        appIconColor = 0x6842FF,
        targets = listOf(
            AppTarget(version = "1.7.13")
        )
    )
}

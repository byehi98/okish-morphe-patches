package app.appusage.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_APP_USAGE = Compatibility(
        name = "App Usage",
        packageName = "com.a0soft.gphone.uninstaller",
        // 6.20 ships as a split .apkm bundle: base.apk plus
        // split_config.{arm64_v8a,armeabi_v7a,x86,x86_64}.apk and the density
        // splits (requiredSplitTypes="base__abi,base__density"). morphe-cli
        // takes the .apkm directly and merges the splits, so do not rebuild a
        // monolithic APK by hand.
        apkFileType = ApkFileType.APKM,
        // Coral red (#FE6C5E) sampled from the launcher icon.
        appIconColor = 0xFE6C5E,
        targets = listOf(
            AppTarget(version = "6.20"),
        ),
    )
}

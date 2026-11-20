package app.appusage.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_APP_USAGE = Compatibility(
        name = "App Usage",
        packageName = "com.a0soft.gphone.uninstaller",
        // 6.19 ships as a split .apks bundle: base.apk plus
        // split_config.{arm64_v8a,armeabi_v7a}.apk and seven density splits
        // (requiredSplitTypes="base__abi,base__density"). morphe-cli takes the
        // .apks directly and merges the splits, so do not rebuild a
        // monolithic APK by hand.
        apkFileType = ApkFileType.APKS,
        // Coral red (#FE6C5E) sampled from the launcher icon.
        appIconColor = 0xFE6C5E,
        targets = listOf(
            AppTarget(version = "6.19"),
        ),
    )
}

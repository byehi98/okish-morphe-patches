package app.sonicdash.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SONIC_DASH = Compatibility(
        name = "Sonic Dash Run",
        packageName = "com.sega.sonicdash",
        // 10.7.0 ships as a split .apks bundle: base.apk plus
        // split_config.arm64_v8a.apk, split_config.armeabi_v7a.apk and
        // split_UnityDataAssetPack.apk. morphe-cli takes the .apks directly and
        // merges the splits, so get("lib/<abi>/libil2cpp.so") resolves to the
        // library from whichever ABI split is present.
        apkFileType = ApkFileType.APKS,
        // Sonic blue sampled from the launcher icon (#3040F0).
        appIconColor = 0x3040F0,
        targets = listOf(
            AppTarget(version = "10.7.0"),
        ),
    )
}

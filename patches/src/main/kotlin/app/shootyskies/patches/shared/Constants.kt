package app.shootyskies.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SHOOTYSKIES = Compatibility(
        name = "Shooty Skies",
        packageName = "com.mightygamesgroup.shootyskies",
        // APKMirror single-APK bundle (arm64-v8a, nodpi).
        apkFileType = ApkFileType.APK,
        // Dominant gold of the sampled app icon (mipmap-xhdpi/app_icon.png).
        appIconColor = 0xF0E000,
        targets = listOf(
            AppTarget(version = "3.441.100104")
        )
    )
}

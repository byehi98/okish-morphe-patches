package app.smashbandits.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SMASH_BANDITS = Compatibility(
        name = "Smash Bandits Racing",
        packageName = "com.hutchgames.smashbandits",
        // 1.10.05.6 ships as a single plain APK (minAPI19, arm64-v8a + armeabi-v7a).
        // The 238 MB asset OBB is a separate download and is never touched by the patch.
        apkFileType = ApkFileType.APK,
        // The launcher icon is a cyan paint-splatter background (#20E7EB) with red/orange
        // cars in front. #00B3DF is the dominant saturated colour of
        // res/mipmap-xxxhdpi/app_icon.png once the white/black splashes are excluded.
        appIconColor = 0x00B3DF,
        targets = listOf(
            AppTarget(version = "1.10.05.6")
        )
    )
}

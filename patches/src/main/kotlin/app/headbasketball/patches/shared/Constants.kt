package app.headbasketball.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_HEAD_BASKETBALL = Compatibility(
        name = "Head Basketball",
        packageName = "com.dnddream.HeadBasketball",
        // Single base APK — the 419 MB game content is a separate OBB
        // (main.471.com.dnddream.HeadBasketball.obb) that lives on the device,
        // NOT a bundle member. So ApkFileType.APK, not XAPK/APKM/APKS.
        apkFileType = ApkFileType.APK,
        // Assumed brand colour (Head Basketball / DN Dream deep navy-blue ball +
        // orange trim). The launcher icon is an obfuscated resource name in
        // 4.6.4, so recon could not sample the exact hue — adjust if the patch UI
        // wants the precise icon colour (same caveat as Dead Trigger's constant).
        appIconColor = 0x1A3A6B,
        targets = listOf(
            AppTarget(version = "4.6.4"),
        ),
    )
}
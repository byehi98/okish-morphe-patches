package app.fancypants.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_FANCYPANTS = Compatibility(
        name = "Fancy Pants Adventures",
        packageName = "com.overthetopgames.fancypants",
        // 1.0.49 is distributed as an XAPK/APKS bundle (base APK + config splits);
        // morphe-cli resolves the bundle itself, so pass the bundle directly.
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFF9800,
        targets = listOf(
            AppTarget(version = "1.0.49")
        )
    )
}

package app.headsoccer.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_HEAD_SOCCER = Compatibility(
        name = "Head Soccer",
        packageName = "com.dnddream.headsoccer.android",
        // The shipped XAPK holds a SINGLE base APK (`com.dnddream.headsoccer.android.apk`,
        // 46 MB) plus a 188 MB OBB expansion — there are no DEX splits and no
        // config.*.apk members. morphe-cli is fed the base APK / XAPK directly and
        // resolves the bundle itself, so the patch targets the plain base APK's DEX.
        apkFileType = ApkFileType.APK,
        // Sampled from the XAPK's own `icon.png` (43,951 B): the launcher art is
        // Head Soccer's deep navy ball (#041260) with a bright blue ring (#0496EC).
        // #0496EC is the more distinctive brand accent, so it drives the chip colour.
        appIconColor = 0x0496EC,
        targets = listOf(
            AppTarget(version = "7.1.6")
        ),
    )
}
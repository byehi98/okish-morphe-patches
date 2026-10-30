package app.headsoccer.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_HEAD_SOCCER = Compatibility(
        name = "Head Soccer",
        packageName = "com.dnddream.headsoccer.android",
        // 7.1.10 ships as a split XAPK: a ~243 MB base APK plus config.arm64_v8a,
        // config.en, config.fr and config.mdpi splits. morphe-cli is fed the XAPK
        // directly and merges the splits itself; the patch targets the base DEX.
        apkFileType = ApkFileType.XAPK,
        // Sampled from the XAPK's own `icon.png` (43,951 B): the launcher art is
        // Head Soccer's deep navy ball (#041260) with a bright blue ring (#0496EC).
        // #0496EC is the more distinctive brand accent, so it drives the chip colour.
        appIconColor = 0x0496EC,
        targets = listOf(
            AppTarget(version = "7.1.11")
        ),
    )
}
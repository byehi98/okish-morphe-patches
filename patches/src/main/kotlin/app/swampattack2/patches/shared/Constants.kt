package app.swampattack2.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SWAMP_ATTACK_2 = Compatibility(
        name = "Swamp Attack 2",
        packageName = "com.hyperdotstudios.swampattack2",
        // APKPure XAPK container: base APK (244 MB) + config.arm64_v8a.apk
        // (libil2cpp.so) — arm64 only, no `requiredSplitTypes` in the
        // manifest; morphe-cli is fed the XAPK directly and resolves the
        // bundle itself (see analysis/.../notes/premium-bypass.md).
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x4C7A26,
        targets = listOf(
            AppTarget(version = "1.3.9"),
        ),
    )
}

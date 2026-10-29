package app.swampattack.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SWAMP_ATTACK = Compatibility(
        name = "Swamp Attack",
        packageName = "com.outfit7.movingeye.swampattack",
        // APKMirror .APKM container (2-arch): base APK + config.arm64_v8a.apk +
        // config.armeabi_v7a.apk. morphe-cli is fed the .apkm directly and resolves
        // the bundle itself (see analysis/swampattack/notes/recon.md).
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x6D9B39,
        targets = listOf(
            AppTarget(version = "4.8.9.0"),
        ),
    )
}

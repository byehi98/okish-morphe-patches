package app.swampattack.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SWAMP_ATTACK = Compatibility(
        name = "Swamp Attack",
        packageName = "com.outfit7.movingeye.swampattack",
        // APKCombo XAPK container: base `com.outfit7.movingeye.swampattack.apk` (235 MB)
        // + `config.arm64_v8a.apk` + `config.armeabi_v7a.apk` + manifest.json.
        // No `requiredSplitTypes` in the manifest — morphe-cli is fed the XAPK directly
        // and resolves the bundle itself (see analysis/swampattack/notes/recon.md).
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x6D9B39,
        targets = listOf(
            AppTarget(version = "4.8.7.0"),
        ),
    )
}

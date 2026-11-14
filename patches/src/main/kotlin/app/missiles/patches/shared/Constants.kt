package app.missiles.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_MISSILES = Compatibility(
        name = "Missiles",
        packageName = "pl.macaque.Missiles",
        // 1.43 ships as a split .apks bundle (APKCube): base.apk plus
        // split_config.arm64_v8a.apk and split_config.armeabi_v7a.apk.
        // morphe-cli takes the .apks directly and merges the splits, so both
        // ABIs are carried through to the patched output.
        apkFileType = ApkFileType.APKS,
        appIconColor = 0x2196F3,
        targets = listOf(
            AppTarget(version = "1.43")
        )
    )
}

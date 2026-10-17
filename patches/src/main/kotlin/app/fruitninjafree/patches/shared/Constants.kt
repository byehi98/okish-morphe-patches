package app.fruitninjafree.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Package name of the target app, used by the compatibility declaration below.
     */
    const val PACKAGE_NAME = "com.halfbrick.fruitninjafree"

    val COMPATIBILITY_FRUIT_NINJA_FREE = Compatibility(
        name = "Fruit Ninja (Free)",
        packageName = PACKAGE_NAME,
        // APKMirror split bundle: base.apk + density splits + ABI splits
        // (requiredSplitTypes="base__abi,base__density"), distributed as .apkm.
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xC36B3E, // dominant quantised colour of the 512x512 launcher icon
        targets = listOf(
            AppTarget(version = "3.97.9") // versionCode 88485200
        )
    )
}

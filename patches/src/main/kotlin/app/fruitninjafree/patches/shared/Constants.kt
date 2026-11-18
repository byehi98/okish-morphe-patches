package app.fruitninjafree.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    /**
     * Package name of the target app, used by the compatibility declaration below.
     */
    const val PACKAGE_NAME = "com.halfbrick.fruitninjafree"

    /**
     * Fruit Ninja Free 3.98.4 (versionCode 90061600).
     *
     * APKM is declared because the APKMirror `.apkm` build is the only bundle
     * that ships BOTH ABI splits (`split_config.arm64_v8a.apk` +
     * `split_config.armeabi_v7a.apk`), which is required for the native
     * `ProcessGooglePlayIAP → ProcessFakeIAP` redirect to be applied to both
     * architectures. The APKPure `.xapk` of this build is arm64-only.
     *
     * Both native anchors (`ProcessFakeIapRedirect`) are re-measured against
     * this exact build — arm64-v8a (file = RVA − 0x4000) and armeabi-v7a
     * (file = RVA) — and occur exactly once in their respective libs.
     */
    val COMPATIBILITY_FRUIT_NINJA_FREE = Compatibility(
        name = "Fruit Ninja (Free)",
        packageName = PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xC36B3E, // dominant quantised colour of the 512x512 launcher icon
        targets = listOf(
            AppTarget(
                version = "3.98.4",
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 90061600,
                    SupportedAbi.ARMEABI_V7A to 90061600,
                ),
            )
        )
    )
}

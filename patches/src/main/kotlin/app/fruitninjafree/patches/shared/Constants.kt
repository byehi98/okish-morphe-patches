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

    val COMPATIBILITY_FRUIT_NINJA_FREE = Compatibility(
        name = "Fruit Ninja (Free)",
        packageName = PACKAGE_NAME,
        // XAPK (APKPure): base.apk + UnityDataAssetPack + config.armeabi_v7a +
        // config.hdpi — ships versionCode 88485200, the build our byte sites
        // target. NOT APKM: APKMirror's .apkm catalog also carries the older
        // versionCode 88439200 build (anchor 80 bytes off → verify-window
        // mismatch), so declaring APKM can route downloads to that build.
        // armeabi-v7a libil2cpp.so is patched (v7a twin in ProcessFakeIapRedirect);
        // arm64 edit is skipped when the XAPK's v7a-only layout is detected.
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xC36B3E, // dominant quantised colour of the 512x512 launcher icon
        targets = listOf(
            AppTarget(
                version = "3.97.9",
                // Only versionCode 88485200 is supported — the older 3.97.9
                // build 88439200 (APKMirror, Sept 22) has the ProcessGooglePlayIAP
                // entry 80 bytes off (file 0x1CEA8D4 vs 0x1CEA874) and fails the
                // native verify window. Declaring the code explicitly lets the
                // manager pick only builds with this versionCode per ABI.
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 88485200,
                    SupportedAbi.ARMEABI_V7A to 88485200
                )
            )
        )
    )
}

package app.deadtarget.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DEAD_TARGET = Compatibility(
        name = "Dead Target: Offline Games 3D",
        packageName = "com.vng.g6.a.zombie",
        // Split bundle. Two shapes exist for 4.186.0:
        //  * APKPure XAPK — base + UnityDataAssetPack.apk + config.armeabi_v7a.apk
        //    (armeabi-v7a only).
        //  * apkcube / current apkeep .apks — base + UnityDataAssetPack.apk +
        //    config.arm64_v8a.apk + config.armeabi_v7a.apk (both ABIs).
        // morphe-cli merges the splits into one APK before any patch executes and the whole
        // bundle is re-signed as a single unit, so the patch set probes and patches every
        // lib/<abi>/libil2cpp.so that ends up in the merged APK.
        //
        // ⚠️ 4.186.0 SHIPS BOTH ARCHITECTURES depending on the mirror. Every anchor set
        // now exists twice: ARM32 (armeabi-v7a, 76,435,716 B) and AArch64 (arm64-v8a,
        // 85,202,576 B). The old "32-bit only" assumption is what caused the CI failure
        // `lib/armeabi-v7a/libil2cpp.so (No such file or directory)` on arm64-only APKs.
        apkFileType = ApkFileType.XAPK,
        // Assumed brand colour (VNG shooter, dead-green HUD) — recon has no icon colour;
        // adjust if the patch UI wants the exact icon hue.
        appIconColor = 0x2E6B32,
        targets = listOf(
            AppTarget(version = "4.186.0"),
        ),
    )
}

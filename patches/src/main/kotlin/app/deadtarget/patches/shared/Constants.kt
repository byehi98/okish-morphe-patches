package app.deadtarget.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DEAD_TARGET = Compatibility(
        name = "Dead Target: Offline Games 3D",
        packageName = "com.vng.g6.a.zombie",
        // Split bundle: XAPK ships base + UnityDataAssetPack.apk + config.armeabi_v7a.apk
        // (morphe-cli merges the splits into one APK before any patch executes, and the
        // whole bundle is re-signed as a single unit).
        //
        // ⚠️ 4.186.0 IS A 32-BIT-ONLY RELEASE. APKPure publishes exactly one variant for
        // this version and it is `armeabi-v7a` (4.185.0 was the arm64-v8a one). Every
        // anchor in this patch set is therefore ARM32 (A32) code from
        // lib/armeabi-v7a/libil2cpp.so, which is 76,435,716 bytes — NOT the 85,096,080-byte
        // arm64 library the previous revision targeted. There is no armeabi-v7a branch to
        // keep in parallel: it is now the only branch.
        apkFileType = ApkFileType.XAPK,
        // Assumed brand colour (VNG shooter, dead-green HUD) — recon has no icon colour;
        // adjust if the patch UI wants the exact icon hue.
        appIconColor = 0x2E6B32,
        targets = listOf(
            AppTarget(version = "4.186.0"),
        ),
    )
}

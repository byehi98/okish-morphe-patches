package app.deadtarget.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DEAD_TARGET = Compatibility(
        name = "Dead Target: Offline Games 3D",
        packageName = "com.vng.g6.a.zombie",
        // Split bundle: XAPK ships base + UnityDataAssetPack.apk + config.arm64_v8a.apk
        // (morphe-cli merges the splits into one APK before any patch executes, and the
        // whole bundle is re-signed as a single unit — see the DELIVERY section of
        // ../unlock/UnlimitedCurrencyPatch.kt). Arm64 ONLY, so no armeabi-v7a branch.
        apkFileType = ApkFileType.XAPK,
        // Assumed brand colour (VNG shooter, dead-green HUD) — recon has no icon colour;
        // adjust if the patch UI wants the exact icon hue.
        appIconColor = 0x2E6B32,
        targets = listOf(
            AppTarget(version = "4.183.0"),
        ),
    )
}
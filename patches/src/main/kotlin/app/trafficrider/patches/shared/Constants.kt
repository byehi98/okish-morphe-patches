package app.trafficrider.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * `com.skgames.trafficrider` 2.21 (versionCode 1010, Unity IL2CPP).
     *
     * Distributions and ABI coverage:
     *  - The APKMirror `.apkm` 2-arch bundle ships **both** `split_config.arm64_v8a`
     *    and `split_config.armeabi_v7a`. morphe-cli is handed the bundle directly
     *    and merges every split, so the patched APK ends up with the
     *    `lib/arm64-v8a/` **and** `lib/armeabi-v7a/` native directories and
     *    installs on either ABI (verified with `aapt dump badging`:
     *    `native-code: 'arm64-v8a' 'armeabi-v7a'`).
     *  - Both patches edit DEX only (`com.android.billingclient.api.*` and
     *    `com.google.unity.ads.*`), and the DEX payload is identical across ABI
     *    splits, so no per-ABI anchors or `versionCodes` map is needed. A single
     *    `AppTarget(version = "2.21")` (empty `versionCodes` = any build of 2.21)
     *    is deliberately used so arm64-only, v7a-only and 2-arch builds all match.
     *  - Only 2.21 is kept in `targets` on purpose: older entries made the CLI
     *    fall back to a stale version's patch set when the APK version could not
     *    be matched exactly.
     */
    val COMPATIBILITY_TRAFFICRIDER = Compatibility(
        name = "Traffic Rider",
        packageName = "com.skgames.trafficrider",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFF5722,
        targets = listOf(
            AppTarget(version = "2.21")
        )
    )
}

package app.trafficrider.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * `com.skgames.trafficrider` 2.20 (versionCode 1006, Unity 6000.5.11f1 IL2CPP).
     *
     * Distributions and ABI coverage:
     *  - The APKPure `.xapk` / APKMirror `.apkm` 2-arch bundles ship **both**
     *    `config.arm64_v8a` and `config.armeabi_v7a`. morphe-cli is handed the
     *    bundle directly and merges every split, so the patched APK ends up with
     *    the `lib/arm64-v8a/` **and** `lib/armeabi-v7a/` native directories and
     *    installs on either ABI (verified with `aapt2 dump badging`:
     *    `native-code: 'arm64-v8a' 'armeabi-v7a'`).
     *  - Both patches edit DEX only (`com.android.billingclient.api.*` and
     *    `com.google.unity.ads.*`), and the DEX payload is identical across ABI
     *    splits, so no per-ABI anchors or `versionCodes` map is needed. A single
     *    `AppTarget(version = "2.20")` (empty `versionCodes` = any build of 2.20)
     *    is deliberately used so arm64-only, v7a-only and 2-arch builds all match.
     *  - A single-ABI APK also patches fine; the merged bundle is only preferred
     *    because it produces one APK usable on both ABIs.
     */
    val COMPATIBILITY_TRAFFICRIDER = Compatibility(
        name = "Traffic Rider",
        packageName = "com.skgames.trafficrider",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFF5722,
        targets = listOf(
            AppTarget(version = "2.20")
        )
    )
}

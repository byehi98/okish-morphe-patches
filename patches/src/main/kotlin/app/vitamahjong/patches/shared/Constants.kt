package app.vitamahjong.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Vita Mahjong 3.43.0 (versionCode 1917).
     *
     * Shipped as an XAPK: base APK (all DEX files) + `config.arm64_v8a` split
     * (all native libraries). The base APK alone cannot install/run, so the
     * bundle must be fed to morphe-cli as-is.
     *
     * The ad and billing targets patched here are Java-only and independent of
     * the IL2CPP/C# layer. No native/IL2CPP patch is attempted: the
     * `global-metadata.dat` header is obfuscated by the Anort shell
     * (`libanort.so`) and no method RVAs are available.
     */
    val COMPATIBILITY_VITA_MAHJONG = Compatibility(
        name = "Vita Mahjong",
        packageName = "com.vitastudio.mahjong",
        apkFileType = ApkFileType.XAPK,
        targets = listOf(
            AppTarget(version = "3.43.0")
        )
    )
}

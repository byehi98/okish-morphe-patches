package app.ninjaarashi2.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    /**
     * Ninja Arashi 2 1.9.6 (versionCode 26) — Unity 6000.2.6f2 / IL2CPP.
     *
     * Published as an **XAPK**: the base APK carries only DEX + `assets/global-metadata.dat`
     * and has **no native libraries at all**. `libil2cpp.so` ships in separate ABI splits:
     *  - `config.arm64_v8a.apk`  (`lib/arm64-v8a/libil2cpp.so`, 52 MB)
     *  - `config.armeabi_v7a.apk` (`lib/armeabi-v7a/libil2cpp.so`, 44 MB)
     *
     * `apkFileType = XAPK` is therefore mandatory: fed as a plain APK the patcher would see
     * no `libil2cpp.so` and every patch here would (correctly) refuse to run. morphe-cli
     * merges all splits, so the patched output still contains both ABIs and installs on
     * either architecture.
     *
     * `global-metadata.dat` is v31 and unencrypted, there is no PairIP / packer / anti-cheat,
     * and nothing in DEX or `Assembly-CSharp` verifies the APK signature, so re-signing is
     * safe and no integrity patch is needed.
     *
     * Both ABI splits carry the same versionCode, so either architecture satisfies the target.
     */
    val COMPATIBILITY_NINJA_ARASHI_2 = Compatibility(
        name = "Ninja Arashi 2",
        packageName = "com.blackpanther.ninjaarashi2",
        apkFileType = ApkFileType.XAPK,
        targets = listOf(
            AppTarget(
                version = "1.9.6",
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 26,
                    SupportedAbi.ARMEABI_V7A to 26,
                ),
            )
        )
    )
}

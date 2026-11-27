package app.ninjaarashi.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    /**
     * Ninja Arashi 1.9.3 (versionCode 18) — Unity 6.2 / IL2CPP.
     *
     * Published as a **single universal APK** (no `.apkm`/`.xapk`, no splits), shipping a
     * full `libil2cpp.so` for both `arm64-v8a` and `armeabi-v7a`. Every gameplay, ad and
     * IAP decision lives in that native library — the only app class in DEX is `R` — so
     * the two native patches here are byte-anchored per ABI and the ad-removal patch only
     * pre-seeds Unity's PlayerPrefs from the launcher activity.
     *
     * Both ABIs carry the same versionCode, so either architecture satisfies the target.
     */
    val COMPATIBILITY_NINJA_ARASHI = Compatibility(
        name = "Ninja Arashi",
        packageName = "com.blackpanther.ninjaarashi",
        apkFileType = ApkFileType.APK,
        targets = listOf(
            AppTarget(
                version = "1.9.3",
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 18,
                    SupportedAbi.ARMEABI_V7A to 18,
                ),
            )
        )
    )
}

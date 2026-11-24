package app.brotato.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Brotato 1.3.951 (versionCode 1030951) — Unity IL2CPP 29, **PairIP v2** protected.
     *
     * Published as an APKM bundle: `base.apk` carries the nine `classes*.dex` files and
     * `assets/`, while every native library lives in the ABI splits
     * (`split_config.{arm64_v8a,armeabi_v7a,x86,x86_64}.apk`). Fed as a plain APK the
     * patcher would see neither `libil2cpp.so` nor a complete install, so
     * `apkFileType = APKM` is mandatory.
     *
     * No per-ABI `versionCodes` map is declared on purpose: all four splits share
     * versionCode 1030951 and the patches in this bundle are either DEX-only (the PairIP
     * bypass) or keyed by per-ABI native anchors, so pinning an ABI here would only make
     * the bundle reject devices that install the x86_64 split (Waydroid/emulators).
     */
    val COMPATIBILITY_BROTATO = Compatibility(
        name = "Brotato",
        packageName = "com.brotato.shooting.survivors.action.roguelike",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xFFC107,
        targets = listOf(
            AppTarget(version = "1.3.951")
        )
    )
}

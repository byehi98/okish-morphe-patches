package app.csrracing.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /** Unity/IL2CPP package name; also the OBB directory name on external storage. */
    const val PACKAGE_NAME = "com.naturalmotion.csrracing"

    /** 64-bit native target (AArch64 code, ARM state). */
    const val LIBIL2CPP_ARM64 = "lib/arm64-v8a/libil2cpp.so"

    /**
     * 32-bit native target (ARMv7, **ARM mode** — not Thumb).
     *
     * Same IL2CPP metadata as arm64, so the same four `PlayerProfile` methods exist and in
     * the same relative order, but every address differs. The addresses are therefore
     * dumped separately (see [app.csrracing.shared.NativeAnchor]).
     */
    const val LIBIL2CPP_ARM32 = "lib/armeabi-v7a/libil2cpp.so"

    val COMPATIBILITY_CSR = Compatibility(
        name = "CSR Racing",
        packageName = PACKAGE_NAME,
        // The patchable input is the fat universal APK, NOT the `.apkm` bundle:
        // CSR 5.1.5 ships one base APK containing BOTH ABIs
        // (lib/arm64-v8a + lib/armeabi-v7a), with no split configs and no APK
        // Signature Scheme v4 side files. morphe-cli would refuse the `.apkm`
        // container and a base-split-only APK would carry no lib/ entries at all.
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE10600,
        targets = listOf(
            // Every anchor RVA is version-specific: IL2CPP compiles to fixed
            // addresses, so the native edits below are valid only for 5.1.5.
            AppTarget(version = "5.1.5"),
        ),
    )
}

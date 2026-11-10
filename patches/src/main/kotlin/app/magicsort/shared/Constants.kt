package app.magicsort.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /** Unity/IL2CPP package name; also embedded in the fake Billing `Purchase` JSON. */
    const val PACKAGE_NAME = "com.grandgames.magicsort"

    val COMPATIBILITY_MAGIC_SORT = Compatibility(
        name = "Magic Sort",
        packageName = PACKAGE_NAME,
        // The ONLY patchable input is the `.apks` split bundle
        // (Magic-Sort_0.0.3420_apkcube.apks = base.apk + split_config.arm64_v8a.apk).
        // The APKPure "universal" APK is the base split alone and carries ZERO
        // lib/ entries, so get("lib/arm64-v8a/libil2cpp.so", true) cannot resolve.
        // morphe-cli merges the bundle itself and emits a single .apk. APKS — not
        // XAPK — because .apks is the actual container format of this download.
        apkFileType = ApkFileType.APKS,
        appIconColor = 0x6C4AB6,
        targets = listOf(
            // Every RVA below is version-specific: IL2CPP compiles to fixed
            // addresses, so the native anchors are only valid for 0.0.3420.
            AppTarget(version = "0.0.3420"),
        ),
    )
}

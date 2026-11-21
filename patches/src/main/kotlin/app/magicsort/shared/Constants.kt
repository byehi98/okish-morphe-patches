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
        // The ONLY patchable input is the split bundle (`.xapk`), which morphe-cli
        // merges itself before patching:
        //   0.0.3536 — base `com.grandgames.magicsort.apk` +
        //              `config.arm64_v8a.apk` + `config.armeabi_v7a.apk` (the full
        //              split bundle — base APK contains no `lib/` entries, both arch
        //              splits do). Some mirrors publish an arm64-only 3536 variant
        //              (Uptodown's default XAPK omits `config.armeabi_v7a.apk`); the
        //              patch skips the missing ABI with a log line.
        // The APKPure "universal" APK is the base split alone and carries ZERO
        // lib/ entries, so get("lib/arm64-v8a/libil2cpp.so", true) cannot resolve.
        // XAPK — not APKS — because .xapk is the actual container format of this
        // download.
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x6C4AB6,
        targets = listOf(
            // Every RVA below is version-specific: IL2CPP compiles to fixed
            // addresses, so the native anchors are only valid for 0.0.3536.
            AppTarget(version = "0.0.3536"),
        ),
    )
}

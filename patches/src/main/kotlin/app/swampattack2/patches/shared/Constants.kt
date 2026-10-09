package app.swampattack2.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SWAMP_ATTACK_2 = Compatibility(
        name = "Swamp Attack 2",
        packageName = "com.hyperdotstudios.swampattack2",
        // XAPK container: base APK + config.arm64_v8a.apk +
        // config.armeabi_v7a.apk splits (the APKPure issue XAPK carries both
        // ABIs; older containers were arm64-only). No `requiredSplitTypes`
        // in the manifest; morphe-cli is fed the XAPK directly and resolves
        // the bundle itself (see analysis/.../notes/premium-bypass.md).
        // Currency is a STATIC byte patch of each ABI's own libil2cpp.so
        // (no launch-time code, no loadLibrary trigger), and ad removal is
        // ABI-independent Java — so both 64-bit and 32-bit devices launch
        // stock code only.
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x4C7A26,
        targets = listOf(
            AppTarget(version = "1.3.9"),
        ),
    )
}

package app.docscanner.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    val COMPATIBILITY_DOCSCANNER = Compatibility(
        name = "Document Scanner",
        packageName = "com.cv.docscanner",
        // 6.9.16 ships from APKMirror as an .apkm bundle: base.apk plus
        // split_config.{arm64_v8a,armeabi_v7a,x86,x86_64}.apk. morphe-cli is fed
        // the .apkm directly and merges the splits itself, so the output keeps
        // every ABI split instead of a hand-built monolithic APK.
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x2196F3,
        targets = listOf(
            AppTarget(
                version = "6.9.16",
                // All four config splits carry the same versionCode (70196) in
                // the universal bundle; listing the ABIs documents that the
                // patch is DEX-only and therefore valid for arm64-v8a,
                // armeabi-v7a and the emulator ABIs alike.
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 70196,
                    SupportedAbi.ARMEABI_V7A to 70196,
                    SupportedAbi.X86_64 to 70196,
                    SupportedAbi.X86 to 70196,
                ),
            )
        )
    )
}

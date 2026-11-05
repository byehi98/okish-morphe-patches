package app.frlegends.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_FR_LEGENDS = Compatibility(
        name = "FR Legends",
        packageName = "com.fengiiley.frlegends",
        // 0.4.9 ships as an XAPK: base.apk plus a mandatory config.arm64_v8a
        // split (the base APK contains no lib/ at all). morphe-cli takes the
        // XAPK directly and merges the splits, so get("lib/arm64-v8a/...")
        // resolves to the library from the ABI split.
        apkFileType = ApkFileType.XAPK,
        // The 0.4.9 launcher icon is pure greyscale — a white/near-black mark
        // over a grey gradient (sampled icon contains no saturated pixels at
        // all). #808080 is the dominant background tone.
        appIconColor = 0x808080,
        targets = listOf(
            AppTarget(version = "0.4.9")
        )
    )
}

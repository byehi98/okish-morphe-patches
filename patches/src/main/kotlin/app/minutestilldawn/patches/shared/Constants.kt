package app.minutestilldawn.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_20MTD = Compatibility(
        name = "20 Minutes Till Dawn",
        description = "20 Minutes Till Dawn — roguelike survival shooter (Flanne)",
        packageName = "com.Flanne.MinutesTillDawn.roguelike.shooting.fr.gp",
        // APKMirror ships this game as .apkm bundles (base.apk + config.<abi> splits).
        // morphe-cli merges the splits into one APK before any patch executes, and the
        // merged APK is re-signed as a single unit.
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x1A1A2E,
        targets = listOf(
            // 8.7 shipped as three bundle builds, all supported by this patch set:
            //   1102 — arm64-v8a only
            //   1104 — arm64-v8a + armeabi-v7a
            //   1105 — arm64-v8a + armeabi-v7a
            //
            // The PairIP replacement strings are keyed by versionCode (see
            // pairip/PoolValues*.kt) because R8 re-shuffles the obfuscated pool class and
            // field names on every build. An unknown build still patches, but its pool
            // is blanked instead of restored, which breaks SQL-building SDK code.
            AppTarget(version = "8.7"),
        )
    )
}

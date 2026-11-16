package app.shootyskies.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SHOOTYSKIES = Compatibility(
        name = "Shooty Skies",
        packageName = "com.mightygamesgroup.shootyskies",
        // Container-agnostic: the CLI merges XAPK/APKM bundles into the base
        // APK before patching, and every patch in this set touches only DEX
        // bytecode (Yodo1 MAS bridge + Play Billing client) — no native libs,
        // so there is nothing ABI-specific to match. The developer ships
        // arm64-v8a only, and the same DEX is used by every container/variant,
        // so one target list covers all distributed forms.
        apkFileType = ApkFileType.APK,
        // Dominant gold of the sampled app icon (mipmap-xhdpi/app_icon.png).
        appIconColor = 0xF0E000,
        targets = listOf(
            // Current release (APKMirror single APK / APKM, XAPK bundles).
            AppTarget(version = "3.441.100105"),
            // Previous release still distributed by some mirrors (APKPure XAPK).
            AppTarget(version = "3.441.100104"),
        )
    )
}

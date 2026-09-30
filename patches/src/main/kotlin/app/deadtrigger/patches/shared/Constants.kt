package app.deadtrigger.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DEAD_TRIGGER = Compatibility(
        name = "Dead Trigger",
        packageName = "com.madfingergames.deadtrigger",
        // Split bundle: manifest declares requiredSplitTypes="base__abi,base__density"
        // and the container carries manifest.json with "xapk_version":"2" (see recon.md).
        apkFileType = ApkFileType.XAPK,
        // Assumed brand colour (Madfinger/Dead Trigger red) — recon has no icon colour;
        // adjust if the patch UI wants the exact icon hue.
        appIconColor = 0xB71C1C,
        targets = listOf(
            AppTarget(version = "2.3.4"),
        ),
    )
}

package app.offtheroad.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_OFF_THE_ROAD = Compatibility(
        name = "Off The Road",
        packageName = "com.dogbytegames.offtheroad",
        // 1.18.2 is distributed as an APKM bundle: a small base APK (4 DEX +
        // billing 7.1.1) plus per-ABI config splits and a ~515 MB `InstallPack`
        // split holding the game assets. morphe-cli is fed the `.apkm` directly
        // and merges the splits itself; every patch here edits DEX only.
        apkFileType = ApkFileType.APKM,
        // Dominant saturated tone sampled from the launcher icon
        // (`res/mipmap-xxxhdpi-v4/ic_launcher.png`) — the desert-sand body of
        // the artwork.
        appIconColor = 0xF5C078,
        targets = listOf(
            AppTarget(version = "1.18.2")
        ),
    )
}

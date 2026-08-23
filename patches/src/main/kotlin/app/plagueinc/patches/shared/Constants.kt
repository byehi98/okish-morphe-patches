package app.plagueinc.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_PLAGUEINC = Compatibility(
        name = "Plague Inc.",
        packageName = "com.miniclip.plagueinc",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xB71C1C,
        targets = listOf(
            AppTarget(version = "1.25.2")
        )
    )
}

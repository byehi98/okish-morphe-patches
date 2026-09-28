package app.docscanner.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_DOCSCANNER = Compatibility(
        name = "Document Scanner",
        packageName = "com.cv.docscanner",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x2196F3,
        targets = listOf(
            AppTarget(version = "6.9.9")
        )
    )
}

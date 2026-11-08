package app.playdigious.tmnt.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * SKUs granted by the full-game unlock:
     *  - premium : the single entitlement source of truth (BuyIAP.HasUnlockedIAP)
     *  - noads   : Play Pass / cross-promo perk (hides "More Games" + GPGS sign-in);
     *              there is no ad SDK in this build, so this is cosmetic
     */
    val TMNT_SKUS = listOf(
        "com.playdigious.tmnt.premium",
        "com.playdigious.tmnt.noads"
    )

    val COMPATIBILITY_TMNT = Compatibility(
        name = "TMNT: Shredder's Revenge",
        packageName = "com.playdigious.tmnt",
        // 1.2.20 is distributed as APKM / XAPK / APKS bundles (base + arm64 + assets splits).
        // morphe-cli resolves each container itself; the downloaded bundle is passed directly.
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x2E6B34,
        targets = listOf(
            AppTarget(version = "1.2.20")
        )
    )
}

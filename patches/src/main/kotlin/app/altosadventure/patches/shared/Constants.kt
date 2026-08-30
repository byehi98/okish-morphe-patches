package app.altosadventure.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * SKUs granted by the "IAP Unlock" patch. Consumable coin packs (coinpack1/2/3)
     * are intentionally excluded — they are not granted at game start. The engine
     * treats a successful queryPurchasesAsync response containing these as "owned",
     * granting every other entitlement (remove-ads, play pass, all characters, coin
     * doubler). Json is never receipt-validated by this app (see premium-bypass.md).
     */
    val SKUS = listOf(
        "com.noodlecake.altosadventure.removeads",
        "com.noodlecake.altosadventure.playpass",
        "com.noodlecake.altosadventure.coindoubler",
        "com.noodlecake.altosadventure.allcharacters",
        "com.noodlecake.altosadventure.allcharacters2",
        "com.noodlecake.altosadventure.allcharacters3",
        "com.noodlecake.altosadventure.allcharacters4",
        "com.noodlecake.altosadventure.felipe",
        "com.noodlecake.altosadventure.izel",
        "com.noodlecake.altosadventure.maya",
        "com.noodlecake.altosadventure.paz",
        "com.noodlecake.altosadventure.tupa"
    )

    val COMPATIBILITY_ALTOSADVENTURE = Compatibility(
        name = "Alto's Adventure",
        packageName = "com.noodlecake.altosadventure",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x5BC0EB,
        targets = listOf(
            AppTarget(version = "1.8.27")
        )
    )
}

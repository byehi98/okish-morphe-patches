package app.altosodyssey.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * SKUs granted by the "IAP Unlock" patch. Consumable coin packs (coinpack1/2/3)
     * are intentionally excluded — they are not granted at game start. The engine
     * treats a successful queryPurchasesAsync response containing these as "owned",
     * granting every other entitlement (remove-ads, play pass, all characters, coin
     * doubler). Json is never receipt-validated (no Unity IAP CrossPlatformValidator,
     * see premium-bypass.md).
     */
    val SKUS = listOf(
        "com.noodlecake.altosodyssey.removeads",
        "com.noodlecake.altosodyssey.playpass",
        "com.noodlecake.altosodyssey.coindoubler",
        "com.noodlecake.altosodyssey.allcharacters",
        "com.noodlecake.altosodyssey.allcharacters2",
        "com.noodlecake.altosodyssey.allcharacters3",
        "com.noodlecake.altosodyssey.allcharacters4",
        "com.noodlecake.altosodyssey.felipe",
        "com.noodlecake.altosodyssey.izel",
        "com.noodlecake.altosodyssey.maya",
        "com.noodlecake.altosodyssey.paz",
        "com.noodlecake.altosodyssey.sumara"
    )

    val COMPATIBILITY_ALTOSODYSSEY = Compatibility(
        name = "Alto's Odyssey",
        packageName = "com.noodlecake.altosodyssey",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xE8A33D,
        targets = listOf(
            AppTarget(version = "1.0.42")
        )
    )
}

package app.trafficracer.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Package name embedded in the fabricated Purchase JSON. Google Play Billing
     * records this against the app, so it MUST match the target package —
     * Traffic Rider's shipped patch hardcodes `com.skgames.trafficrider` there,
     * this app is `com.skgames.trafficracer`.
     */
    const val PACKAGE_NAME_TRAFFICRACER = "com.skgames.trafficracer"

    /**
     * SKUs returned as "owned" by the `queryPurchasesAsync` hook (BILL-5).
     *
     * Non-consumables only — the acknowledgePurchase set from iap-bypass.md §3.
     * A successful queryPurchasesAsync response containing these is treated as
     * "owned" by Unity IAP 5.0.3's local entitlement path
     * (GooglePlayCheckEntitlementUseCase), so remove-ads / starter-kit /
     * premium-car / cash-doubler survive restarts (and are granted at startup).
     *
     * Consumable cash packs (cash3x..cash7x) are intentionally excluded: their
     * grants already persist through SecureSaveGameManager on disk, and
     * re-granting them on every launch is not desired (same decision as the
     * Alto's Adventure IAP patch).
     *
     * Product IDs verified against IL2CPP string literals in
     * global-metadata.dat (stringliteral.json) — they match the nine
     * MyIAPManager.buy*() entry points 1:1 (buyRemoveAds → remove_ads,
     * buyDoubleCash → cash_doubler, buyStarterKit → starter_kit,
     * buyPremiumCar1 → premium_car_1). Note: the hunt notes' variants
     * `CASH_DOUBLER` / `starterkit` / `premiumcar1` are Globals *field names*,
     * never used as string literals by the game code.
     */
    val SKUS = listOf(
        "remove_ads",
        "cash_doubler",
        "starter_kit",
        "premium_car_1"
    )

    val COMPATIBILITY_TRAFFICRACER = Compatibility(
        name = "Traffic Racer",
        packageName = "com.skgames.trafficracer",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x8BC34A, // dominant color of ic_launcher.png (green)
        targets = listOf(
            AppTarget(version = "4.0")
        )
    )
}

package app.crashofcars.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Package name embedded in the fabricated `Purchase` JSON produced by the
     * `launchBillingFlow` and `queryPurchasesAsync` hooks.
     *
     * Google Play Billing records this field against the app and Unity IAP
     * echoes it back to the C# layer, so it MUST match the target package or
     * the synthetic receipt is discarded instead of granting.
     */
    const val PACKAGE_NAME = "com.notdoppler.crashofcars"

    /**
     * Product IDs reported as already-owned by the `queryPurchasesAsync` hook,
     * which is what makes the entitlement survive an app restart (UPM re-queries
     * owned purchases at startup and re-grants without a tap).
     *
     * **Deliberately one entry.** Per `analysis/com.notdoppler.crashofcars/notes/targets.md`
     * §3, `purchase_remove_ads` is the *only* Google Play product ID that could be
     * recovered from the APK — it is a proper Unity-serialized string at OBB
     * offset `0x5B6236`. Every other candidate found in the OBB
     * (`iap_halloween_sale`, `coin_pack_small`, `vip_offer`, `buy_gems`, …) sits
     * in the same literal pool as localization keys and is an **offer/config
     * key**, not a Play SKU: the real coin/gem product IDs are fetched at runtime
     * from the Not Doppler backend (`get_ProductionEnvironmentUrl`,
     * `daily_offers_offline`, `gem_pack_timer` are all present in
     * `global-metadata.dat`) and are simply not shippable in the binary.
     *
     * Guessing them would be worse than useless — a wrong productId is dropped by
     * UPM's cart validator (`NoMatchingCatalogItemForReceipt`) and the grant never
     * lands. The grant-on-tap path (hook #1) is SKU-agnostic anyway: it reads the
     * productId straight off the tapped `BillingFlowParams`, so consumable packs
     * work on tap without ever being listed here.
     */
    val SKUS = listOf(
        "purchase_remove_ads"
    )

    val COMPATIBILITY_CRASH_OF_CARS = Compatibility(
        name = "Crash of Cars",
        packageName = "com.notdoppler.crashofcars",
        // Bare base APK, not the APKPure XAPK — the 153 MB OBB is a separate
        // file that lives on the device and is never re-signed by Morphe.
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0080E0, // dominant quantised colour of the 512x512 launcher icon
        targets = listOf(
            AppTarget(version = "1.9.34")
        )
    )
}
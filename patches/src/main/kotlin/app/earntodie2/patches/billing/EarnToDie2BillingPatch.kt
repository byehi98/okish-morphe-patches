package app.earntodie2.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.earntodie2.patches.shared.Constants.COMPATIBILITY_EARNTODIE2

@Suppress("unused")
val earnToDie2BillingPatch = bytecodePatch(
    name = "Earn to Die 2 Free IAP",
    description = "Intercepts all in-app purchases and calls the native on_purchase(String[], String) grant directly, unlocking paid content (cash doubler) for free without Play Billing.",
    default = true
) {
    compatibleWith(COMPATIBILITY_EARNTODIE2)

    execute {
        // InAppPurchases.PurchaseProduct(String sku) is invoked via JNI by the
        // Cocos2d-x C++ engine whenever the player taps a purchase in the shop.
        // Normally it opens the Play billing flow; on success the real path is
        // InAppPurchases$PurchaseListener.ProcessPurchase(Purchase), which calls
        // the native InAppPurchases.on_purchase(String[] products, String token)
        // and lets C++ BillingHandler::OnPurchaseComplete grant the item.
        //
        // 1.5.6 note: the legacy single-argument purchase-complete native
        // callback does NOT exist in this version (verified across all 10 dex
        // trees — 0 hits). Calling it would throw NoSuchMethodError on the
        // first instruction, so return-void would never run and nothing would
        // be granted. The only native grant entry point
        // is on_purchase([Ljava/lang/String;Ljava/lang/String;)V, so we call
        // that directly, then return-void. No billing connection is needed —
        // on_purchase is a direct native grant and Play Billing never
        // initializes on the test device anyway.
        //
        // Register safety: PurchaseProduct is `public static`, `.registers 6`,
        // 1 String parameter → p0 is v5 and locals v0..v4 are free. This
        // injection uses only v0, v1, v2 — safe.
        //
        // arg 1 = String[]{sku} — mirrors the real flow's
        // Purchase.getProducts().toArray(new String[0]).
        //
        // arg 2 = the purchase TOKEN, not the SKU (the real flow passes
        // getPurchaseToken()). It must be unique per call
        // (UUID.randomUUID()) because C++ de-dupes consumables — the native
        // string table contains "Consumable purchase was already granted", so
        // a constant token (e.g. the SKU) would block repeat purchases of the
        // same coin pack.
        //
        // We deliberately do NOT populate
        // InAppPurchases.purchaseListener.pendingPurchases: a fake token there
        // would only cause bogus ConsumePurchase/AcknowledgePurchase calls,
        // and OnPurchaseProcessed(String,boolean) null-checks the map entry
        // and safely no-ops when absent.
        PurchaseProductFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            new-array v0, v0, [Ljava/lang/String;
            const/4 v1, 0x0
            aput-object p0, v0, v1
            invoke-static {}, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;
            move-result-object v2
            invoke-virtual {v2}, Ljava/util/UUID;->toString()Ljava/lang/String;
            move-result-object v2
            invoke-static {v0, v2}, Lcom/notdoppler/billing/InAppPurchases;->on_purchase([Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())
    }
}

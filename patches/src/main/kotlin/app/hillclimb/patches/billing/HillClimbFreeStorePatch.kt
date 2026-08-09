package app.hillclimb.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.hillclimb.patches.shared.Constants.COMPATIBILITY_HILLCLIMB

// Smali class descriptors. The \$ escapes keep Kotlin string interpolation
// from treating "$IapItem" as a template expression.
private const val BILLING_HANDLE = "Lcom/fingersoft/billing/NewBillingHandle;"
private const val IAP_ITEM = "Lcom/fingersoft/billing/NewBillingHandle\$IapItem;"
private const val IAP_STORE = "Lcom/fingersoft/game/InAppPurchaseStore;"

/**
 * Hill Climb Racing — Free Store
 *
 * StartPurchase(productId) is the single entry point for every store item.
 * Instead of launching Google Play billing, we look the item up in
 * NewBillingHandle.mIaps (fallback: mPopupIaps), grant its contents directly
 * via InAppPurchaseStore (coins, gems, paints, ad-skips, ad-free, bundle) and
 * mark the product as processed — so every store item is free and instant.
 *
 * The grant block mirrors NewBillingHandle.handleSuccessOfTheConsume
 * (NewBillingHandle.smali:1310), which is the game's own "purchase granted"
 * logic, including the unconditional setAdFree call.
 *
 * Register budget: StartPurchase has .registers 6 (locals v0-v3, p0=this,
 * p1=productId). We use v0 (IapItem / Map), v1 (Context), v2 (int value).
 *
 * Confirmed smali: NewBillingHandle.smali:1237.
 */
@Suppress("unused")
val hillClimbFreeStorePatch = bytecodePatch(
    name = "Hill Climb Racing Free Store",
    description = "Every store item is granted instantly and free: coins, gems, paints, ad-skips, ad-free and bundles, without launching Google Play billing.",
    default = true
) {
    compatibleWith(COMPATIBILITY_HILLCLIMB)

    execute {
        StartPurchaseFingerprint.method.addInstructionsWithLabels(0, """
            iget-object v0, p0, $BILLING_HANDLE->mIaps:Ljava/util/Map;
            invoke-interface {v0, p1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $IAP_ITEM
            if-nez v0, :grant
            iget-object v0, p0, $BILLING_HANDLE->mPopupIaps:Ljava/util/Map;
            invoke-interface {v0, p1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $IAP_ITEM
            if-eqz v0, :done
            :grant
            iget-object v1, p0, $BILLING_HANDLE->mainActivity:Landroid/app/Activity;
            iget v2, v0, $IAP_ITEM->coins:I
            invoke-static {v1, v2}, $IAP_STORE->addCoins(Landroid/content/Context;I)V
            iget v2, v0, $IAP_ITEM->gems:I
            invoke-static {v1, v2}, $IAP_STORE->addGems(Landroid/content/Context;I)V
            iget v2, v0, $IAP_ITEM->paints:I
            invoke-static {v1, v2}, $IAP_STORE->addPaints(Landroid/content/Context;I)V
            iget v2, v0, $IAP_ITEM->adskips:I
            invoke-static {v1, v2}, $IAP_STORE->addAdSkips(Landroid/content/Context;I)V
            invoke-static {v1}, $IAP_STORE->setAdFree(Landroid/content/Context;)V
            iget v2, v0, $IAP_ITEM->bundle:I
            invoke-static {v1, v2}, $IAP_STORE->setBundle(Landroid/content/Context;I)V
            invoke-static {v1, p1}, $IAP_STORE->markAsProcessed(Landroid/content/Context;Ljava/lang/String;)V
            :done
            return-void
            nop
        """.trimIndent())
    }
}

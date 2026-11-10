package app.playdigious.tmnt.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.playdigious.tmnt.patches.shared.Constants.COMPATIBILITY_TMNT
import app.playdigious.tmnt.patches.shared.Constants.TMNT_SKUS

private const val BILLING_RESULT = "Lcom/android/billingclient/api/BillingResult;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val PURCHASE = "Lcom/android/billingclient/api/Purchase;"
private const val PURCHASES_LISTENER = "Lcom/android/billingclient/api/PurchasesResponseListener;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val LIST = "Ljava/util/List;"

/**
 * Builds smali injected at the very top of
 * BillingClientImpl.queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener)V.
 *
 * It fabricates an OK BillingResult and a List<Purchase> carrying BOTH SKUs in a
 * single `productIds` array, delivers it through p2, then returns before the real
 * Google Play query runs.
 *
 * Register usage (.registers 9 -> v0..v5 free):
 *   v0 = OK BillingResult (reused as builder scratch)
 *   v1 = ArrayList<Purchase>
 *   v2 = JSON string
 *   v3 = responseCode 0 / Purchase instance
 *   v4 = signature "morphe"
 *   p2 = PurchasesResponseListener (untouched)
 *
 * JSON notes (verified against Purchase.java in this build):
 *  - `productIds` is the array read by getSkus()/getProducts(); `productId` would
 *    only work as a fallback, so use the array.
 *  - purchaseState 0 -> getPurchaseState() = 1 (PURCHASED); 4 would mean PENDING.
 *  - acknowledged true -> no AcknowledgePurchase round-trip.
 */
private fun fakePurchaseSmali(): String {
    val products = TMNT_SKUS.joinToString(separator = ",") { "\"$it\"" }
    val json = (
        "{\"packageName\":\"com.playdigious.tmnt\"," +
            "\"productIds\":[$products]," +
            "\"purchaseState\":0," +
            "\"purchaseToken\":\"morphe\"," +
            "\"acknowledged\":true," +
            "\"quantity\":1," +
            "\"autoRenewing\":false}"
        ).replace("\"", "\\\"")

    return """
        invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER
        move-result-object v0
        const/4 v3, 0x0
        invoke-virtual {v0, v3}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
        move-result-object v0
        invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT
        move-result-object v0

        new-instance v1, $ARRAY_LIST
        invoke-direct {v1}, $ARRAY_LIST-><init>()V

        const-string v2, "$json"
        const-string v4, "morphe"
        new-instance v3, $PURCHASE
        invoke-direct {v3, v2, v4}, $PURCHASE-><init>(Ljava/lang/String;Ljava/lang/String;)V
        invoke-interface {v1, v3}, $LIST->add(Ljava/lang/Object;)Z

        invoke-interface {p2, v0, v1}, $PURCHASES_LISTENER->onQueryPurchasesResponse($BILLING_RESULT$LIST)V
        return-void
    """.trimIndent()
}

@Suppress("unused")
val tmntFullGameUnlockPatch = bytecodePatch(
    name = "Full Game Unlock",
    description = "Unlocks the full game, arcade, survival, custom game and DLC characters by faking Google Play purchase query results.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TMNT)

    execute {
        QueryPurchasesFingerprint.method.addInstructions(0, fakePurchaseSmali())
    }
}

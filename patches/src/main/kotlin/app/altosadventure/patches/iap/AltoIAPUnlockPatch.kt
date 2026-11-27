package app.altosadventure.patches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.altosadventure.patches.shared.Constants.COMPATIBILITY_ALTOSADVENTURE
import app.altosadventure.patches.shared.Constants.SKUS

// Smali class descriptors
private const val BILLING_RESULT = "Lcom/android/billingclient/api/BillingResult;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val PURCHASE = "Lcom/android/billingclient/api/Purchase;"
private const val PURCHASES_LISTENER = "Lcom/android/billingclient/api/PurchasesResponseListener;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val LIST = "Ljava/util/List;"

private const val PACKAGE_NAME = "com.noodlecake.altosadventure"

/**
 * Builds smali that, at the top of `BillingClientImpl.queryPurchasesAsync`, fabricates an
 * OK BillingResult and a List<Purchase> containing a `new Purchase(json, "")` for EVERY SKU,
 * delivers it through `PurchasesResponseListener.onQueryPurchasesResponse(...)` and
 * `return-void`s, so the real Play query never runs.
 *
 * Register usage (method declares `.registers 10` with 3 params → v0..v6 free):
 *   v0 = OK BillingResult (then reused as builder scratch)
 *   v1 = ArrayList<Purchase>
 *   v2 = current SKU json string
 *   v3 = scratch (responseCode 0 / current Purchase instance)
 *   v4 = empty signature string ("")
 *
 * p0 = this (BillingClientImpl), p1 = QueryPurchasesParams, p2 = PurchasesResponseListener.
 * We return before touching p0/p1, so only p2 (the listener) is consumed.
 */
private fun fakePurchasesSmali(): String {
    val json = { sku: String ->
        (
            "{" +
                "\"orderId\":\"GPA.0000-0000-0000-00000\"," +
                "\"packageName\":\"$PACKAGE_NAME\"," +
                "\"productId\":\"$sku\"," +
                "\"productIds\":[\"$sku\"]," +
                "\"purchaseTime\":1700000000000," +
                "\"purchaseState\":1," +
                "\"purchaseToken\":\"$sku\"," +
                "\"acknowledged\":true," +
                "\"autoRenewing\":false," +
                "\"quantity\":1" +
                "}"
            ).replace("\"", "\\\"")
    }

    val sb = StringBuilder()

    // --- Build OK BillingResult: BillingResult.newBuilder().setResponseCode(0).build() ---
    sb.appendLine("invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER")
    sb.appendLine("move-result-object v0")
    sb.appendLine("const/4 v3, 0x0")
    sb.appendLine("invoke-virtual {v0, v3}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER")
    sb.appendLine("move-result-object v0")
    sb.appendLine("invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT")
    sb.appendLine("move-result-object v0")

    // --- Build ArrayList<Purchase> ---
    sb.appendLine("new-instance v1, $ARRAY_LIST")
    sb.appendLine("invoke-direct {v1}, $ARRAY_LIST-><init>()V")
    sb.appendLine("const-string v4, \"\"")

    // --- One Purchase per SKU, added to the list ---
    for (sku in SKUS) {
        sb.appendLine("const-string v2, \"${json(sku)}\"")
        sb.appendLine("new-instance v3, $PURCHASE")
        sb.appendLine("invoke-direct {v3, v2, v4}, $PURCHASE-><init>(Ljava/lang/String;Ljava/lang/String;)V")
        sb.appendLine("invoke-virtual {v1, v3}, $ARRAY_LIST->add(Ljava/lang/Object;)Z")
    }

    // --- Deliver the faked response and bail out before the real query ---
    sb.appendLine("invoke-interface {p2, v0, v1}, $PURCHASES_LISTENER->onQueryPurchasesResponse($BILLING_RESULT$LIST)V")
    sb.appendLine("return-void")

    return sb.toString().trimIndent()
}

@Suppress("unused")
val altoIAPUnlockPatch = bytecodePatch(
    name = "IAP Unlock",
    description = "Unlocks all in-app purchases by faking Google Play billing query results.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ALTOSADVENTURE)

    execute {
        QueryPurchasesFingerprint.method.addInstructions(0, fakePurchasesSmali())
    }
}

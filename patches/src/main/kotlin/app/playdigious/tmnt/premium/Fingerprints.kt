package app.playdigious.tmnt.patches.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.android.billingclient.api.BillingClientImpl
 *   .method public final queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener)V
 *
 * Verified at analysis/tmnt/smali/classes/com/android/billingclient/api/BillingClientImpl.smali:10616
 *   .registers 9  -> v0..v5 are free locals, p0 = v6, p1 = v7, p2 = v8
 *
 * This is the Java entry point behind the managed call
 * AndroidServices.FetchPurchases() -> BillingClient.QueryPurchases(params, listener).
 * The billing 8.0.0 library ships unobfuscated, so the public names are stable;
 * no string anchors are required.
 */
object QueryPurchasesFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryPurchasesAsync",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryPurchasesParams;",
        "Lcom/android/billingclient/api/PurchasesResponseListener;"
    )
)

package app.altosadventure.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.android.billingclient.api.BillingClientImpl
 *     public final void queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener)
 *
 * 1.8.34 bumped the bundled Play Billing Library to v7.x, which dropped the old
 * internal `zzaI(String, PurchasesResponseListener)` choke point used by the
 * previous patch. The public entry point is the one Unity IAP's C# layer
 * (`GooglePlayStoreService`, confirmed present in global-metadata.dat) calls via
 * AndroidJavaProxy, so it is the new single choke point for "what do I own?".
 *
 * Verified against smali (classes2.dex → BillingClientImpl.smali):
 *   .method public final queryPurchasesAsync(Lcom/android/billingclient/api/QueryPurchasesParams;Lcom/android/billingclient/api/PurchasesResponseListener;)V
 *       .registers 10
 *       ...
 *       invoke-interface {p2, p1, v0}, ...PurchasesResponseListener->onQueryPurchasesResponse(...)V
 *
 * The name is a public, non-obfuscated Billing API, so it is pinned directly and
 * additionally guarded by the `onQueryPurchasesResponse` call filter.
 */
object QueryPurchasesFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryPurchasesAsync",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryPurchasesParams;",
        "Lcom/android/billingclient/api/PurchasesResponseListener;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/PurchasesResponseListener;",
            name = "onQueryPurchasesResponse"
        )
    )
)

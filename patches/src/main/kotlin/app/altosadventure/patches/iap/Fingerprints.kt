package app.altosadventure.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.android.billingclient.api.BillingClientImpl
 *     private final void zzaI(String, PurchasesResponseListener)  (smali line 1609)
 *
 * This is the real choke point behind `queryPurchasesAsync`. It guards on
 * `isReady()` and on every path calls
 * `purchasesResponseListener.onQueryPurchasesResponse(BillingResult, List<Purchase>)`.
 *
 * The method name `zzaI` is obfuscated and NOT used here — the fingerprint is
 * pinned by stable, update-resistant characteristics instead:
 *   - defining class (BillingClientImpl, non-obfuscated)
 *   - private final, return void
 *   - exact parameter list (String, PurchasesResponseListener)
 *   - filter order: an `isReady()` call first, then a
 *     `PurchasesResponseListener.onQueryPurchasesResponse(...)` call.
 *
 * Verified against smali (classes/com/android/billingclient/api/BillingClientImpl.smali):
 *   .method private final zzaI(Ljava/lang/String;Lcom/android/billingclient/api/PurchasesResponseListener;)V
 *       .registers 11
 *       invoke-virtual {p0}, ...->isReady()Z            ; first instruction
 *       ...
 *       invoke-interface {p2, p1, v0}, ...PurchasesResponseListener->onQueryPurchasesResponse(...)V
 */
object QueryPurchasesFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "Lcom/android/billingclient/api/PurchasesResponseListener;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
            name = "isReady"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/PurchasesResponseListener;",
            name = "onQueryPurchasesResponse"
        )
    )
)

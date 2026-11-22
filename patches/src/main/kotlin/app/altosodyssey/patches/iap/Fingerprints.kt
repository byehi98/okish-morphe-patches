package app.altosodyssey.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * `com.android.billingclient.api.BillingClientImpl` (Google Play Billing 8.3.0)
 *
 *     public final queryPurchasesAsync(
 *         Lcom/android/billingclient/api/QueryPurchasesParams;,
 *         Lcom/android/billingclient/api/PurchasesResponseListener;)V
 *
 * Pinned against the **public, non-obfuscated** Billing API instead of the internal
 * `zzaI(String, PurchasesResponseListener)` choke point used for 1.0.42: Billing
 * 8.x removed that method. `queryPurchasesAsync` now builds a `zzbm` Callable and a
 * `zzbj` Runnable and hands them to `zzK(...)`; the real Play query runs inside
 * `zzbm.call()` → `zzar(...)` → `zzdz`. Injecting at the top of
 * `queryPurchasesAsync` short-circuits all of that, so the obfuscated helper names
 * (`zzbm`/`zzbj`/`zzar`) never have to be fingerprinted.
 *
 * Verified against smali (classes2.dex, BillingClientImpl.smali line 11789):
 *   .method public final queryPurchasesAsync(
 *       Lcom/android/billingclient/api/QueryPurchasesParams;,
 *       Lcom/android/billingclient/api/PurchasesResponseListener;)V
 *       .registers 10
 *       invoke-virtual {p1}, ...QueryPurchasesParams;->zza()Ljava/lang/String;
 *       ...
 *       invoke-interface {p2, p1, v0}, ...PurchasesResponseListener;->
 *           onQueryPurchasesResponse(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
 *
 * `.registers 10` with 3 parameters (p0/p1/p2 = v7/v8/v9) leaves v0..v6 free for
 * the injected fake-purchase block.
 */
object QueryPurchasesAsyncFingerprint : Fingerprint(
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

package app.onlyone.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google IAP Billing v2 plugin (Corona) ────────────────────────────────────
// The whole `plugin.google.iap.billing.v2` package keeps its names because the
// plugin is loaded BY NAME from Lua (require("plugin.google.iap.billing.v2")
// inside resource.car → IAPGoogle.lu) — obfuscating it would break the game.

/**
 * [LuaLoader.onPurchasesUpdated(BillingResult, List)][LuaLoader] — the single
 * PurchasesUpdatedListener sink of the plugin (LuaLoader implements the
 * interface and passes itself to BillingClient.Builder.setListener).
 * Verified shape (classes3/.../LuaLoader.smali line 2251):
 *
 *   if-eqz p2, :cond_45                          // null-list guard
 *   ...List;->iterator()...                      // per-row loop
 *   ...Purchase;->getOriginalJson()...           // → Security.verifyPurchase
 *   ...Security;->verifyPurchase(...)Z
 *   ...StoreTransactionRuntimeTask;-><init>(Purchase, BillingResult, I)V
 *   ...CoronaRuntimeTaskDispatcher;->send(...)
 *   :cond_45 ...                                 // null-list → "unknown" task
 *
 * Hook: no bytecode change in this method itself — morpheFakePurchase calls
 * it with a genuinely-built OK BillingResult, so the StoreTransactionRuntimeTask
 * ctor reads responseCode==0 → state "purchased" for every row handed over.
 * (Security.verifyPurchase is pinned separately — see the patch. Do NOT null
 * the p1 register to "force OK": p1 IS the BillingResult object, and the task
 * ctor immediately dereferences it → NPE on every Buy tap.)
 */
object OnPurchasesUpdatedFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
    name = "onPurchasesUpdated",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/BillingResult;",
        "Ljava/util/List;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
            name = "verifyPurchase"
        ),
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/StoreTransactionRuntimeTask;",
            name = "<init>"
        ),
        methodCall(
            definingClass = "Lcom/ansca/corona/CoronaRuntimeTaskDispatcher;",
            name = "send"
        )
    )
)

/**
 * [LuaLoader.purchaseType(LuaState, QueryPurchasesParams)][LuaLoader] — the
 * shared engine of Lua purchase() and purchaseSubscription() (both are
 * one-line delegates passing INAPP/SUBS params). Verified shape
 * (classes3/.../LuaLoader.smali line 1297):
 *
 *   ...LuaLoader;->initSuccessful()Z               // connect guard
 *   const/4 v7, 0x0                                // "nothing pushed" retval
 *   ...LuaState;->toString(1) → v5                 // productId
 *   new BillingFlowParams$Builder / $SubscriptionUpdateParams$Builder
 *   accountId/profileId hashing (try/catch regions!)
 *   sget-object v0, ...LuaLoader;->fCachedProductDetails:Ljava/util/HashMap;
 *   [cached]  ...BillingClient;->launchBillingFlow(...)      [line 1959]
 *   [uncached] ...BillingClient;->queryProductDetailsAsync(LuaLoader$8)
 *
 * Hook: inject a straight-line guard immediately before the fCachedProductDetails
 * sget — v2 (this, aliased at head), v5 (productId) and v7 (0) are all live
 * and unmodified there; the guard fabricates + dispatches the purchase and
 * returns v7, so the Play sheet never opens regardless of cache state.
 */
object LuaLoaderPurchaseTypeFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
    name = "purchaseType",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(
        "Lcom/naef/jnlua/LuaState;",
        "Lcom/android/billingclient/api/QueryPurchasesParams;"
    ),
    filters = listOf(
        methodCall(definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;", name = "initSuccessful"),
        fieldAccess(smali = "Lplugin/google/iap/billing/v2/LuaLoader;->fCachedProductDetails:Ljava/util/HashMap;"),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "launchBillingFlow"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "queryProductDetailsAsync"
        )
    )
)

/**
 * [LuaLoader$8.onProductDetailsResponse(BillingResult, QueryProductDetailsResult)]
 * [LuaLoader$8] — the fetch-then-buy continuation for SKUs missing from
 * fCachedProductDetails. Verified shape (classes3/.../LuaLoader$8.smali):
 *
 *   ...BillingResult;->getResponseCode()I            // error branch guard
 *   ...QueryProductDetailsResult;->getProductDetailsList()
 *   ...HashMap;->put(productId, details)             // via -$$Nest$sfget…
 *   ...BillingClient;->launchBillingFlow(...)        // [line 292]
 *   const-string "Error while purchasing because Product Id was not found"
 *
 * Hook: prepend `invoke-direct {p0, p2}` + `return-void`. p2 (the result
 * object) is dead at entry — its first original write is the iterator move —
 * and p1 only feeds the error branches that the early return never reaches.
 * NOTE: the second parameter is the concrete QueryProductDetailsResult class
 * in this billing version (not the bare List interface), which is what makes
 * this method uniquely matchable.
 */
object ProductDetailsResponseFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader\$8;",
    name = "onProductDetailsResponse",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/BillingResult;",
        "Lcom/android/billingclient/api/QueryProductDetailsResult;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
            name = "-\$\$Nest\$sfgetfCachedProductDetails"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "launchBillingFlow"
        ),
        string("Error while purchasing because Product Id was not found")
    )
)

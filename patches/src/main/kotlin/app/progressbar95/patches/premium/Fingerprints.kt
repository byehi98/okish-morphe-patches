package app.progressbar95.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Progressbar95 1.1110 — Corona/Solar2D IAP bridge fingerprints ─────────────
//
// Game decisions live in compiled Lua (assets/resource.car); Lua reaches the
// platform ONLY through this Java bridge (plugin.google.iap.billing.v2). All
// names below are stable Google/Corona SDK names — never obfuscated — per the
// ubisoftpop stable-SDK-anchor doctrine.
//
// Verified against:
//   analysis/progressbar95/smali/classes6/plugin/google/iap/billing/v2/util/Security.smali
//   analysis/progressbar95/smali/classes6/plugin/google/iap/billing/v2/LuaLoader.smali

/**
 * T1 — `Security.verifyPurchase(String, String, String)Z`
 * (Security.smali, `.registers 6`, public static).
 *
 * RSA/SHA1withRSA receipt check over (licenseKey, originalJson, signature).
 * Patch forces it to `true` (headsoccer VerifyPurchase precedent), so every
 * `storeTransaction` dispatched from a real Play purchase is treated as genuine.
 */
object VerifyPurchaseFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
    name = "verifyPurchase",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "isEmpty",
        ),
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
            name = "generatePublicKey",
        ),
    ),
)

/**
 * T3 anchor — `LuaLoader.onPurchasesUpdated(BillingResult, List<Purchase>)V`
 * (LuaLoader.smali, `.registers 8`, public).
 *
 * The single funnel for every purchase result: iterates the purchase list,
 * calls `Security.verifyPurchase`, and dispatches a `StoreTransactionRuntimeTask`
 * (the exact object Lua listens to as `storeTransaction`).
 *
 * NOTE (T8): the old T3 prepend here is REMOVED — it fired downstream of Play
 * (fail popup, then grant). This fingerprint now serves only as the class
 * anchor for injecting `morpheFakePurchase` into `LuaLoader`, and the forged
 * event flows through this method via that helper (upstream of Play, so no
 * sheet ever opens).
 */
object OnPurchasesUpdatedFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
    name = "onPurchasesUpdated",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/BillingResult;",
        "Ljava/util/List;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
            name = "verifyPurchase",
        ),
        string("Signature verification failed!"),
    ),
)

/**
 * T8a — `LuaLoader.purchaseType(LuaState, QueryPurchasesParams)I`
 * (LuaLoader.smali:1297-2019, `private`, `.registers 20`).
 *
 * The single funnel for Lua `purchase()` (inapp) and `purchaseSubscription()`
 * (SUBS) — both are one-line trampolines passing INAPP/SUBS params. Two
 * `launchBillingFlow` sites downstream: cached-details path (line 1949) and
 * uncached path via `LuaLoader$8` (T8b). Filters in smali order:
 * `initSuccessful` (1305) → init-guard string (1316) → `fCachedProductDetails`
 * sget (1805) → `launchBillingFlow` (1949) → `queryProductDetailsAsync` (2014).
 *
 * Guard register contract at BOTH insertion sites (verified, never copied
 * from OnlyOne): v7 = this (`move-object/from16 v7, p0`, line 1300, never
 * rewritten), v4 = productId (line 1339; the null path returns at 1349, the
 * next v4 write is line 2008 — after both guards), v8 = 0 (line 1309,
 * returned by every early exit and the tail, never rewritten).
 */
object LuaLoaderPurchaseTypeFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
    name = "purchaseType",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf(
        "Lcom/naef/jnlua/LuaState;",
        "Lcom/android/billingclient/api/QueryPurchasesParams;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
            name = "initSuccessful",
        ),
        string("Please call init before trying to purchase products."),
        fieldAccess(smali = "Lplugin/google/iap/billing/v2/LuaLoader;->fCachedProductDetails:Ljava/util/HashMap;"),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "launchBillingFlow",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "queryProductDetailsAsync",
        ),
    ),
)

/**
 * T8b — `LuaLoader$8.onProductDetailsResponse(BillingResult,
 * QueryProductDetailsResult)V` (LuaLoader$8.smali:76-325, `public`,
 * `.registers 6`).
 *
 * Belt-and-braces second `launchBillingFlow` (line 290) for the uncached
 * path. NOTE vs OnlyOne: the SKU is NOT `p2` here — params are
 * `(BillingResult, QueryProductDetailsResult)`, and the requested SKU lives
 * in the synthetic field `val$productId` (read at lines 127, 211). Guard C
 * therefore does `iget-object sku, val$productId` + dispatches via `this$0`.
 * Filters in smali order: Nest cache read (112) → `launchBillingFlow`
 * (290) → not-found error string (296).
 */
object ProductDetailsResponseFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader\$8;",
    name = "onProductDetailsResponse",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/BillingResult;",
        "Lcom/android/billingclient/api/QueryProductDetailsResult;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
            name = "-\$\$Nest\$sfgetfCachedProductDetails",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "launchBillingFlow",
        ),
        string("Error while purchasing because Product Id was not found"),
    ),
)

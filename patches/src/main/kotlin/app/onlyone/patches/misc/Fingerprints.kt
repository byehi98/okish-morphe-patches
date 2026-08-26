package app.onlyone.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── PairIP license check ──────────────────────────────────────────────────────
// PairIP (Google Play integrity wrapper) keeps unobfuscated package/class/method
// names — they are anchored by the manifest-registered LicenseContentProvider
// and are identical across all PairIP-injected apps and versions.

/**
 * LicenseClient.initializeLicenseCheck()V — public entry point called by the
 * manifest-registered [com.pairip.licensecheck.LicenseContentProvider.onCreate]
 * before Application.onCreate. It is the sole fan-out of the entire PairIP
 * chain: local installer check, licensing-service bind, CHECK_LICENSE_V2
 * parcel transact, response validation, paywall/error dialog, repeated checks.
 *
 * Confirmed smali (classes2/com/pairip/licensecheck/LicenseClient.smali:1593):
 *   .method public initializeLicenseCheck()V
 *   .registers 3
 *     sget-object v0, ...->licenseCheckState:L...LicenseCheckState;   // state machine read
 *     invoke-virtual {v0}, ...LicenseCheckState;->ordinal()I          // state dispatch
 *     ...
 *     invoke-static {v0, v1}, ...LicenseResponseHelper;->validateResponse(...)V  // JWS check
 *     invoke-direct {p0, v0}, ...->handleError(L...LicenseCheckException;)V      // failure path
 *
 * Stable ordered filters pin the method shape beyond its name:
 * licenseCheckState field read → ordinal() dispatch → validateResponse → handleError.
 */
object PairipInitializeLicenseCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            smali = "Lcom/pairip/licensecheck/LicenseClient;" +
                "->licenseCheckState:Lcom/pairip/licensecheck/LicenseClient\$LicenseCheckState;"
        ),
        methodCall(name = "ordinal"),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;",
            name = "validateResponse"
        ),
        methodCall(name = "handleError")
    )
)

// ── Google IAP Billing v2 plugin (Corona) ────────────────────────────────────
// The whole `plugin.google.iap.billing.v2` package keeps its names because the
// plugin is loaded BY NAME from Lua (require("plugin.google.iap.billing.v2")
// inside resource.car → IAPGoogle.lu) — obfuscating it would break the game.

/**
 * [LuaLoader.restore(Lcom/naef/jnlua/LuaState;)I][LuaLoader] — Lua-facing
 * "Restore Purchases" entry point. Verified shape (classes4/.../LuaLoader.smali
 * line 2033):
 *
 *   invoke-direct {p0}, ...LuaLoader;->initSuccessful()Z          // connect guard
 *   iput v0, p0, ...LuaLoader;->numOfRestoreResults:I             // reset counter
 *   ...BillingResult$Builder;->setResponseCode(I)...              // res starts OK(0)
 *   new-instance v1, Ljava/util/ArrayList;                        // ← shared result list
 *   ...
 *   ...BillingClient;->queryPurchasesAsync(SUBS,  LuaLoader$6)    // subs response
 *   ...BillingClient;->queryPurchasesAsync(INAPP, LuaLoader$7)    // in-app response
 *
 * Both anonymous listeners ($6/$7) capture that ArrayList and funnel the merged
 * purchase rows through onPurchasesUpdated — which is exactly why injecting
 * fabricated Purchases into the list right after its construction inherits all
 * existing verification/dispatch/ack plumbing for free.
 */
object LuaLoaderRestoreFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;",
    name = "restore",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/naef/jnlua/LuaState;"),
    filters = listOf(
        methodCall(definingClass = "Lplugin/google/iap/billing/v2/LuaLoader;", name = "initSuccessful"),
        fieldAccess(smali = "Lplugin/google/iap/billing/v2/LuaLoader;->numOfRestoreResults:I"),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingResult\$Builder;",
            name = "setResponseCode"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingClient;",
            name = "queryPurchasesAsync"
        )
    )
)

/**
 * [Security.verifyPurchase(String, String, String)][Security] — the ONLY
 * cryptographic gate of the IAP bridge (SHA1withRSA against the embedded
 * license key). Verified shape (classes4/.../util/Security.smali line 185):
 *
 *   ...TextUtils;->isEmpty(...)Z ×3            // signedData / pubkey / signature
 *   ...Security;->generatePublicKey(...)Ljava/security/PublicKey;
 *   ...Security;->verify(PublicKey, String, String)Z
 *   const-string "android.test.purchased"      // legacy test-SKU fallback branch
 *
 * Contrary to free-store-feasibility.md §4#1 ("restore path never verifies"),
 * THIS build's restore listeners route through onPurchasesUpdated (verified:
 * LuaLoader$6.smali:162, LuaLoader$7.smali:173), so every purchase row — real
 * or fabricated — must pass this gate to become a storeTransaction event. The
 * empty-signature fallback only accepts productId == "android.test.purchased",
 * which then fails validateReceipt's len(signature) > 10 check on the Lua side,
 * so there is no JSON trick that survives without opening this gate.
 */
object SecurityVerifyPurchaseFingerprint : Fingerprint(
    definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
    name = "verifyPurchase",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = "Landroid/text/TextUtils;", name = "isEmpty"),
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
            name = "generatePublicKey"
        ),
        methodCall(
            definingClass = "Lplugin/google/iap/billing/v2/util/Security;",
            name = "verify"
        ),
        string("android.test.purchased")
    )
)

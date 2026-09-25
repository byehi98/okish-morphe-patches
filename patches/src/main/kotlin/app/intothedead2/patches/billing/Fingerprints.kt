package app.intothedead2.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.pikpok.AndroidStore.PurchaseUIThread(String, boolean)V — private.
 *
 * Single entry point for every store tap (Purchase(String,Z) → AndroidStore$8.run
 * → -$$Nest$mPurchaseUIThread → here). Builds BillingFlowParams from ProductDetails
 * (Play Billing 7 — was SkuDetails pre-1.87.1) and calls BillingClient.launchBillingFlow.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1018 (.registers 5).
 * We replace the whole body with a fabricated purchase-success call.
 *
 * Filters are in exact instruction order (verified against smali):
 *   "No billing client" → "Iap type not supported"
 *   → "Null sku details for product id"
 *   → BillingFlowParams$ProductDetailsParams$Builder.setProductDetails
 *   → BillingClient.launchBillingFlow
 *
 * 1.87.1 (Play Billing 6→7 migration) dropped BillingFlowParams$Builder.setSkuDetails
 * in favour of BillingFlowParams$ProductDetailsParams$Builder.setProductDetails;
 * the string literals either side of it are unchanged, so only filter 4 moved.
 *
 * NOTE: these are the Into the Dead 2 strings. Do NOT use ITD1's
 * "Billing client not ready" / ReQuerySingle / LaunchBillingflow — they do
 * not exist in this title (gating here is via iapSupported/subsSupported
 * fields + ProductDetails map lookup).
 */
object PurchaseUIThreadFingerprint : Fingerprint(
    definingClass = "Lcom/pikpok/AndroidStore;",
    name = "PurchaseUIThread",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Ljava/lang/String;", "Z"),
    filters = listOf(
        string("No billing client"),
        string("Iap type not supported"),
        string("Null sku details for product id"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams\$Builder;", name = "setProductDetails"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "launchBillingFlow"),
    )
)

/**
 * com.pikpok.AndroidStore.Verify(String, String, String)Z — public.
 *
 * Java-side SHA1withRSA receipt verification, callable from C# via JNI.
 * Must accept our fabricated "morphe-signature" or the grant may fail.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1545 (.registers 6).
 * Filters are in exact instruction order (verified against smali):
 *   GetPublicKey → "SHA1withRSA" → Signature.getInstance → initVerify → verify
 *
 * Byte-identical pattern to ITD1 — fingerprint transfers unchanged (T2).
 */
object VerifyFingerprint : Fingerprint(
    definingClass = "Lcom/pikpok/AndroidStore;",
    name = "Verify",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/pikpok/AndroidStore;", name = "GetPublicKey"),
        string("SHA1withRSA"),
        methodCall(definingClass = "Ljava/security/Signature;", name = "getInstance"),
        methodCall(definingClass = "Ljava/security/Signature;", name = "initVerify"),
        methodCall(definingClass = "Ljava/security/Signature;", name = "verify"),
    )
)

/**
 * com.pikpok.AndroidStore.ConsumeUIThread(String token, String productId, String extra)V — private.
 *
 * Consumable grant gate. After T1 fabricates PurchaseSuccess with token
 * "morphe-token", C# calls Consume(token, productId, …) → here, which forwards
 * the fake token to BillingClient.consumeAsync. Play rejects it
 * (logcat PID 16779: "Error consuming purchase with token. Response code: 5"
 * → Unity "IAPManager: Consume purchase Failed! … Invalid token."), so C#
 * never grants. We replace the whole body with a direct ConsumeSuccess
 * call — the fake purchase reports success without reaching Play.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:543 (.registers 7).
 * Filters are in exact instruction (file) order (verified against smali):
 *   "Billing client null" → BillingClient.isReady → "Billing Client not ready"
 *   → "Unknown product " → ConsumeParams$Builder.setPurchaseToken
 *   → BillingClient.consumeAsync
 */
object ConsumeUIThreadFingerprint : Fingerprint(
    definingClass = "Lcom/pikpok/AndroidStore;",
    name = "ConsumeUIThread",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        string("Billing client null"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "isReady"),
        string("Billing Client not ready"),
        string("Unknown product "),
        methodCall(definingClass = "Lcom/android/billingclient/api/ConsumeParams\$Builder;", name = "setPurchaseToken"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "consumeAsync"),
    )
)

/**
 * com.pikpok.AndroidStore.AcknowledgeUIThread(String token, String productId, String extra)V — private.
 *
 * Same failure mode as ConsumeUIThread but for non-consumables/subs:
 * Acknowledge(token, …) → here → BillingClient.acknowledgePurchase(fake token)
 * → Play rejects → AcknowledgeFailed → no grant. Short-circuit to
 * AcknowledgeSuccess so durables also grant without reaching Play.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:257 (.registers 7).
 * Filters are in exact instruction (file) order (verified against smali):
 *   "Billing client null" → BillingClient.isReady → "Billing Client not ready"
 *   → "Unknown product " → AcknowledgePurchaseParams$Builder.setPurchaseToken
 *   → BillingClient.acknowledgePurchase
 */
object AcknowledgeUIThreadFingerprint : Fingerprint(
    definingClass = "Lcom/pikpok/AndroidStore;",
    name = "AcknowledgeUIThread",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        string("Billing client null"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "isReady"),
        string("Billing Client not ready"),
        string("Unknown product "),
        methodCall(definingClass = "Lcom/android/billingclient/api/AcknowledgePurchaseParams\$Builder;", name = "setPurchaseToken"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "acknowledgePurchase"),
    )
)

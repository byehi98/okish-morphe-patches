package app.intothedead.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.pikpok.AndroidStore.PurchaseUIThread(String, boolean)V — private.
 *
 * Called on the UI thread (via AndroidStore$8.run → -$$Nest$mPurchaseUIThread) whenever
 * the player taps "buy" in the PikPok store. Normally it validates the billing client,
 * looks up SkuDetails and launches the real Google Play billing dialog.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1206 (.registers 5).
 * We replace the whole body with a fabricated purchase-success call.
 *
 * Filters are in exact instruction order (verified against smali):
 *   "No billing client" → BillingClient.isReady → "Billing client not ready"
 *   → "Iap type not supported" → ReQuerySingle → LaunchBillingflow
 */
object PurchaseUIThreadFingerprint : Fingerprint(
    definingClass = "Lcom/pikpok/AndroidStore;",
    name = "PurchaseUIThread",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Ljava/lang/String;", "Z"),
    filters = listOf(
        string("No billing client"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "isReady"),
        string("Billing client not ready"),
        string("Iap type not supported"),
        methodCall(definingClass = "Lcom/pikpok/AndroidStore;", name = "ReQuerySingle"),
        methodCall(definingClass = "Lcom/pikpok/AndroidStore;", name = "LaunchBillingflow"),
    )
)

/**
 * com.pikpok.AndroidStore.Verify(String, String, String)Z — public.
 *
 * Java-side SHA1withRSA receipt verification. C# can invoke it via JNI after a
 * purchase; returning true accepts any receipt (including our fabricated one).
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1932 (.registers 6).
 * Filters are in exact instruction order (verified against smali):
 *   GetPublicKey → "SHA1withRSA" → Signature.getInstance → initVerify → verify
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

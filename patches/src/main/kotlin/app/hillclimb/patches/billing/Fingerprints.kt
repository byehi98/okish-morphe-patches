package app.hillclimb.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * NewBillingHandle.StartPurchase(String) — public instance method, called on
 * the UI thread when the player taps ANY store item (coins, gems, paints,
 * ad-free, bundles, special offers).
 *
 * Confirmed smali: NewBillingHandle.smali:1237 (classes3.dex). It resolves
 * the ProductDetails via replaceGetOrDefault, logs "Starting purchase of : ",
 * then launches the Google Play billing flow via BillingClient.launchBillingFlow.
 *
 * Filters are in exact instruction order: the replaceGetOrDefault call, the
 * log string, then the launchBillingFlow call.
 */
object StartPurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/fingersoft/billing/NewBillingHandle;",
    name = "StartPurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/fingersoft/billing/NewBillingHandle;", name = "replaceGetOrDefault"),
        string("Starting purchase of : "),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "launchBillingFlow")
    )
)

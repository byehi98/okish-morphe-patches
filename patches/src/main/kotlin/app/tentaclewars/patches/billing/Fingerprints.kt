package app.tentaclewars.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Billing.buyProductImpl(Activity, String): boolean — the ONLY path that launches the Google
 * Play purchase flow. Call chain smali-verified: native buyProduct(String) → MainActivity.buyProduct
 * (@JvmStatic, MainActivity.smali:208) → Companion.buyProduct (Companion.smali:445) →
 * Billing.buyProduct (Billing.smali:1360) → buyProductImpl (Billing.smali:371–533).
 *
 * App code is NOT obfuscated (names stable because JNI resolves them), but we match structurally:
 *   - string "Can't buy product " — unique log message (Billing.smali:435)
 *   - BillingClient.launchBillingFlow (Billing.smali:512) and BillingResult.getResponseCode
 *     (Billing.smali:521) — filters are ordered exactly as they appear in smali.
 *
 * Replacing this body lets us fire _onPurchaseUpdate(productId, true) directly, which routes through
 * the existing lambda bridge (MainActivity.onCreate$lambda$0) into native onPurchaseUpdate(String, Z)
 * — the identical signal the game receives from a genuinely completed purchase.
 */
object BillingBuyProductImplFingerprint : Fingerprint(
    definingClass = "Lcom/tw/Billing;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;"),
    filters = listOf(
        string("Can't buy product "),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "launchBillingFlow"),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingResult;", name = "getResponseCode"),
    ),
)

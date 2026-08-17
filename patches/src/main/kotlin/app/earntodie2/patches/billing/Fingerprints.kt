package app.earntodie2.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * InAppPurchases.PurchaseProduct(String) — called by the Cocos2d-x C++ engine
 * (via JNI) whenever the player taps a purchase in the shop. Normally it
 * launches the Google Play billing flow via launchBillingFlow.
 *
 * Confirmed smali (classes4/com/notdoppler/billing/InAppPurchases.smali:209):
 *   .method public static PurchaseProduct(Ljava/lang/String;)V
 *   .registers 6
 *   const-string v0, "PurchaseProduct"
 *   invoke-static {v0}, ...->Log(Ljava/lang/String;)V
 *   invoke-static {}, ...->IsConnected()Z
 *   ...launchBillingFlow...
 *
 * Stable filters: the "PurchaseProduct" log tag and the IsConnected() gate.
 */
object PurchaseProductFingerprint : Fingerprint(
    definingClass = "Lcom/notdoppler/billing/InAppPurchases;",
    name = "PurchaseProduct",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        string("PurchaseProduct"),
        methodCall(
            definingClass = "Lcom/notdoppler/billing/InAppPurchases;",
            name = "IsConnected"
        )
    )
)

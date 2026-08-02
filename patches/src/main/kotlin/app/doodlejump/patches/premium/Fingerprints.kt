package app.doodlejump.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * GooglePlayBillingManager.handleMessage(int, Object, int)
 *
 * This is the Java-side dispatcher that receives messages from the C++ engine.
 * When msg ID 0x28 (Msg_IAP_PurchaseItem) arrives, it queues a real billing
 * flow which opens the Google Play purchase tab.
 *
 * We intercept here to detect msg 0x28 and immediately fire back a fake
 * success MsgIAPTransactionData instead of launching the billing UI.
 *
 * Confirmed smali (classes/com/limasky/doodlejumpandroid/GooglePlayBillingManager.smali):
 *   .method public handleMessage(ILjava/lang/Object;I)I
 *   Contains: const/16 p3, 0x28  (line checking for PurchaseItem msg)
 *   Then: iget-boolean ... isPurchaseOptionEnabled
 */
object HandleMessageFingerprint : Fingerprint(
    definingClass = "Lcom/limasky/doodlejumpandroid/GooglePlayBillingManager;",
    name = "handleMessage",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I", "Ljava/lang/Object;", "I"),
    filters = listOf(
        literal(0x28),    // const/16 for Msg_IAP_PurchaseItem
        methodCall(
            definingClass = "Ljava/util/LinkedList;",
            name = "addLast"
        )
    )
)

/**
 * GooglePlayBillingManager.onRestoreInventoryFinished()
 *
 * Called on every startup when billing restores existing purchases.
 * We inject a fake unlock here so the engine is notified on launch
 * even when there is no pending purchase request.
 */
object OnRestoreInventoryFinishedFingerprint : Fingerprint(
    definingClass = "Lcom/limasky/doodlejumpandroid/GooglePlayBillingManager;",
    name = "onRestoreInventoryFinished",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/BillingResult;",
        "Ljava/util/List;",
        "Lcom/limasky/doodlejumpandroid/GooglePlayBillingManager\$JobRequest;"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingResult;",
            name = "getResponseCode"
        )
    )
)

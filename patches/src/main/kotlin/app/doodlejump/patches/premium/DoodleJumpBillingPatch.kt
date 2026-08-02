package app.doodlejump.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.doodlejump.patches.shared.Constants.COMPATIBILITY_DOODLEJUMP

// Smali class descriptors
private const val NOTIFICATION_CENTER = "Lcom/limasky/doodlejumpandroid/NotificationCenter;"
private const val MSG_IAP_DATA = "Lcom/limasky/doodlejumpandroid/Messages\$MsgIAPTransactionData;"
private const val MSG_IAP_PURCHASE_DATA = "Lcom/limasky/doodlejumpandroid/Messages\$MsgIAPPurchaseItemData;"
private const val SKU = "unlock_full_game"

/**
 * Smali that creates a MsgIAPTransactionData with status=0 (Success) for
 * "unlock_full_game" and sends it via NotificationCenter.sendMessageThreadSafe.
 *
 * Uses v0..v2. Safe at index 0 (onRestoreInventoryFinished has 7 registers,
 * and handleMessage injection uses a label jump so registers are isolated).
 *
 * 0x29 = Msg_IAP_Transaction (confirmed in Messages.smali)
 * 0x0  = IAPStatus.Success    (confirmed in IAPStatus.smali)
 */
private fun fakePurchaseSmali(skuRegister: String = "v1") = """
    new-instance v0, $MSG_IAP_DATA
    invoke-direct {v0}, $MSG_IAP_DATA-><init>()V
    const-string $skuRegister, "$SKU"
    iput-object $skuRegister, v0, $MSG_IAP_DATA->sku:Ljava/lang/String;
    const/4 v2, 0x0
    iput v2, v0, $MSG_IAP_DATA->status:I
    const/16 $skuRegister, 0x29
    invoke-static {$skuRegister, v0, v2, v2}, $NOTIFICATION_CENTER->sendMessageThreadSafe(ILjava/lang/Object;II)I
""".trimIndent()

@Suppress("unused")
val doodleJumpBillingPatch = bytecodePatch(
    name = "Doodle Jump Billing Bypass",
    description = "Unlocks the full game by bypassing Google Play billing.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DOODLEJUMP)

    execute {
        // --- Patch 1: handleMessage intercept ---
        // When C++ sends Msg_IAP_PurchaseItem (0x28), we intercept it and
        // immediately return a fake success transaction instead of opening
        // the Google Play billing tab.
        //
        // Strategy: at index 0 check if p1 == 0x28, if so fire fake success and return.
        // handleMessage has .registers 8, so v0..v4 are safe locals.
        HandleMessageFingerprint.method.addInstructionsWithLabels(0, """
            const/16 v0, 0x28
            if-ne p1, v0, :skip_purchase_intercept
            if-eqz p2, :skip_purchase_intercept
            check-cast p2, $MSG_IAP_PURCHASE_DATA
            iget-object v1, p2, $MSG_IAP_PURCHASE_DATA->sku:Ljava/lang/String;
            new-instance v0, $MSG_IAP_DATA
            invoke-direct {v0}, $MSG_IAP_DATA-><init>()V
            iput-object v1, v0, $MSG_IAP_DATA->sku:Ljava/lang/String;
            const/4 v2, 0x0
            iput v2, v0, $MSG_IAP_DATA->status:I
            const/16 v1, 0x29
            invoke-static {v1, v0, v2, v2}, $NOTIFICATION_CENTER->sendMessageThreadSafe(ILjava/lang/Object;II)I
            const/4 v0, 0x0
            return v0
            :skip_purchase_intercept
            nop
        """)

        // --- Patch 2: onRestoreInventoryFinished startup unlock ---
        // On every app start, billing restores purchases. We inject a fake
        // success here so the C++ engine receives the unlock even if the
        // purchase was never made. Uses v0..v2 (method has .registers 7).
        OnRestoreInventoryFinishedFingerprint.method.addInstructions(0, fakePurchaseSmali())
    }
}

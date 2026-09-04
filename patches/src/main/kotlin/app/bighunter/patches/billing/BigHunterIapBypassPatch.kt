package app.bighunter.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.bighunter.patches.shared.Constants.COMPATIBILITY_BIG_HUNTER

/**
 * IAP bypass for Big Hunter: tapping any store item grants the product instantly — no
 * Google Play dialog, no billing connection.
 *
 * Replaces startPurchase(String key)'s body with the game's own confirmed-purchase path
 * (addAndSaveUserPurchasedKey -> buyedProductKeyList.add -> notifyPurchased), which ends
 * in the native applyProduct() grant and persists the entitlement in SharedPreferences.
 * This is exactly what the game does when an already-purchased item is re-confirmed.
 *
 * IMPORTANT (delayed-credit bug fix): the very first instruction must be
 * disableUserUI(). The 500 ms postDelayed runnable that notifyPurchased() schedules
 * calls enableUserUI(), which is gated on isDisableUserUI:
 *
 *     enableUserUI():
 *         iget-boolean v0, p0, ...->isDisableUserUI:Z
 *         if-nez v0, :cond_5
 *         return-void            # <-- silent no-op when flag is false
 *         :cond_5
 *         ... applyPurchasedProducts() -> applyProduct() -> native grant ...
 *
 * Without disableUserUI() the flag stays false, enableUserUI() returns without ever
 * calling applyProduct(), and the purchase is only persisted to SharedPreferences
 * (__SP/__upk) — the coins/gems then appear only later when the native side re-syncs
 * owned products (e.g. returning to the main menu). The original startPurchase() calls
 * disableUserUI() before the Already-Purchased branch; the patch must do the same so the
 * grant fires immediately. disableUserUI() also fires native startProcess() (the
 * processing indicator) which the original flow shows during the 500 ms window.
 *
 * Register map (startPurchase: .registers 10 -> v8=this, v9=key):
 *   v9 = product key (read-only param), v0 = KKJPaymentGoogle instance, v1 = list.
 * The inline smali compiler rejects pN registers, so numeric v8/v9 are used.
 */
@Suppress("unused")
val bigHunterIapBypassPatch = bytecodePatch(
    name = "Big Hunter Fake Purchase",
    description = "Tapping any store item grants the product immediately (via the game's own confirmed-purchase path -> native applyProduct) without opening Google Play billing. Entitlements persist across restarts.",
) {
    compatibleWith(COMPATIBILITY_BIG_HUNTER)

    execute {
        StartPurchaseFingerprint.method.addInstructions(
            0,
            """
            invoke-virtual {v8}, LkakarodJavaLibs/data/KKJPaymentGoogle;->disableUserUI()V
            invoke-static {v9}, LkakarodJavaLibs/data/KKJPaymentGoogle;->addAndSaveUserPurchasedKey(Ljava/lang/String;)V
            invoke-static {}, LkakarodJavaLibs/data/KKJPaymentGoogle;->getInstance()LkakarodJavaLibs/data/KKJPaymentGoogle;
            move-result-object v0
            iget-object v1, v0, LkakarodJavaLibs/data/KKJPaymentGoogle;->buyedProductKeyList:Ljava/util/ArrayList;
            invoke-virtual {v1, v9}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            invoke-direct {v0}, LkakarodJavaLibs/data/KKJPaymentGoogle;->notifyPurchased()V
            return-void
            """.trimIndent(),
        )
    }
}
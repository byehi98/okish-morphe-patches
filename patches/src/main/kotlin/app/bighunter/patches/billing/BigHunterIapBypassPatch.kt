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

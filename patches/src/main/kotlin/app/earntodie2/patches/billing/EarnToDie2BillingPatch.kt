package app.earntodie2.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.earntodie2.patches.shared.Constants.COMPATIBILITY_EARNTODIE2

@Suppress("unused")
val earnToDie2BillingPatch = bytecodePatch(
    name = "Earn to Die 2 Free IAP",
    description = "Intercepts all in-app purchases and reports instant success to the game engine, unlocking paid content (cash doubler) for free.",
    default = true
) {
    compatibleWith(COMPATIBILITY_EARNTODIE2)

    execute {
        // InAppPurchases.PurchaseProduct(String sku) is invoked via JNI by the
        // Cocos2d-x C++ engine whenever the player taps a purchase in the shop.
        // Normally it opens the Play billing flow; on success the PurchaseListener
        // calls InAppPurchases.on_purchase_complete(sku) — a native method — which
        // tells the C++ engine to grant the item.
        //
        // We skip the entire billing flow and call on_purchase_complete(p0)
        // directly with the requested SKU, then return-void. The engine sees a
        // completed purchase and grants the cash doubler without any payment.
        //
        // PurchaseProduct has .registers 6 (p0 = String sku, v0..v4 free) and is
        // static — p0 is valid at index 0. on_purchase_complete signature:
        //   (Ljava/lang/String;)V  — native, grants the SKU in C++.
        PurchaseProductFingerprint.method.addInstructions(0, """
            invoke-static {p0}, Lcom/notdoppler/billing/InAppPurchases;->on_purchase_complete(Ljava/lang/String;)V
            return-void
        """.trimIndent())
    }
}

package app.intothedead.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.intothedead.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD

/**
 * Into the Dead — Free IAP
 *
 * com.pikpok.AndroidStore.PurchaseUIThread(String productId, boolean isSubscription)V
 * is the single entry point for every store purchase (tapped "buy" → AndroidStore$8.run
 * → -$$Nest$mPurchaseUIThread → PurchaseUIThread). Normally it validates the billing
 * client, looks up SkuDetails and launches the real Google Play billing dialog.
 *
 * We replace the whole body: build a fabricated Google Play purchase JSON with the
 * REAL tapped productId (p1), then call PurchaseSuccess(json, fakeSignature) directly.
 * PurchaseSuccess → AndroidStoreInterface.PurchaseSuccess → SinglePurchaseDataHolder +
 * UnitySendMessage("RequestPurchaseSuccess") — the exact same path a real Play
 * purchase takes when it lands in onPurchasesUpdated (getPurchaseState()==1).
 * C# then parses GetPurchaseData() (which returns our JSON) and grants the item.
 *
 * JSON "purchaseState":0 is deliberately used: the billing library's
 * Purchase.getPurchaseState() (classes4, smali:294) returns PURCHASED(1) for any
 * value except 4, so our receipt is classified as a successful purchase everywhere.
 *
 * Register budget: PurchaseUIThread has .registers 5 (locals v0-v1, p0=this,
 * p1=productId, p2=isSubscription). The injected block only uses v0, v1, p0, p1.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1206.
 */
@Suppress("unused")
val intoTheDeadFreeIAPPatch = bytecodePatch(
    name = "Into the Dead Free IAP",
    description = "Unlocks all in-app purchases for free: every store item is granted instantly without launching the Google Play payment dialog.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD)

    execute {
        PurchaseUIThreadFingerprint.method.addInstructions(0, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.sidheinteractive.sif.DR\",\"productId\":\""
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\",\"purchaseState\":0,\"purchaseToken\":\"morphe-token\",\"purchaseTime\":0,\"acknowledged\":false}"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            const-string v1, "morphe-signature"
            invoke-direct {p0, v0, v1}, Lcom/pikpok/AndroidStore;->PurchaseSuccess(Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent())
    }
}

package app.intothedead.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.intothedead.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD

/**
 * Into the Dead — Billing Bypass
 *
 * Single consolidated patch replacing the previous two billing patches
 * (Free IAP + Verify Bypass). The execute block runs the Free IAP section
 * first (fabricated purchase-success), then the Verify bypass section.
 *
 * ============================================================
 * SECTION 1 — Free IAP
 * ============================================================
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
 *
 * ============================================================
 * SECTION 2 — Verify Bypass
 * ============================================================
 *
 * com.pikpok.AndroidStore.Verify(String purchaseData, String signature, String publicKey)Z
 * performs SHA1withRSA receipt verification (classes7/com/pikpok/AndroidStore.smali:1932).
 * C# can invoke it via JNI after a purchase; if it rejects our fabricated signature the
 * grant may fail, so this section makes Verify always return true.
 *
 * The Free IAP section fabricates a purchase receipt with a dummy signature; this
 * backup ensures any Java-side receipt verification accepts it.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1932 (.registers 6).
 */
@Suppress("unused")
val intoTheDeadBillingBypassPatch = bytecodePatch(
    name = "Into the Dead Billing Bypass",
    description = "Unlocks all in-app purchases for free and bypasses SHA1withRSA receipt verification: every store item is granted instantly without launching the Google Play payment dialog, and any fabricated receipt is accepted.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD)

    execute {
        // ==========================================================
        // SECTION 1 — Free IAP: fabricate a successful purchase for
        // the REAL tapped productId (p1) via PurchaseSuccess.
        // ==========================================================
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

        // ==========================================================
        // SECTION 2 — Verify Bypass: accept any receipt (including
        // our fabricated signature from Section 1).
        // ==========================================================
        VerifyFingerprint.method.returnEarly(true)
    }
}

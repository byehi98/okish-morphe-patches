package app.intothedead2.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.intothedead2.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD_2

/**
 * Into the Dead 2 — Free IAP (T1 + T2 companion).
 *
 * com.pikpok.AndroidStore.PurchaseUIThread(String productId, boolean isSubscription)V
 * is the single entry point for every store purchase (tapped "buy" → AndroidStore$8.run
 * → -$$Nest$mPurchaseUIThread → PurchaseUIThread). Normally it validates the billing
 * client, checks iapSupported/subsSupported, looks up SkuDetails and launches the
 * real Google Play billing dialog.
 *
 * We replace the whole body: build a fabricated Google Play purchase JSON with the
 * REAL tapped productId (p1), then call PurchaseSuccess(json, fakeSignature) directly.
 * PurchaseSuccess → AndroidStoreInterface.PurchaseSuccess → SinglePurchaseDataHolder +
 * UnitySendMessage("RequestPurchaseSuccess") — the exact same path a real Play
 * purchase takes when it lands in onPurchasesUpdated. C# then parses GetPurchaseData()
 * (which returns our JSON) and grants the item.
 *
 * Register budget: PurchaseUIThread has .registers 5 (locals v0-v1, p0=this,
 * p1=productId, p2=isSubscription). The injected block only uses v0, v1, p0, p1.
 *
 * Confirmed smali: classes7/com/pikpok/AndroidStore.smali:1018.
 *
 * T2 COMPANION (required — device logcat PID 16779 proved T1 alone dies here):
 * after T1 fires, C# calls Consume(fake "morphe-token", …) → ConsumeUIThread →
 * BillingClient.consumeAsync → Play rejects ("Error consuming purchase with token.
 * Response code: 5" → Unity "IAPManager: Consume purchase Failed! … Invalid
 * token."), so the grant never lands and the tap appears to do nothing. Hence:
 *  - ConsumeUIThread body → direct ConsumeSuccess(p1, p2, p3) + return-void
 *    (fake purchase reports success without reaching Play). Smali:543 (.registers 7,
 *    no new locals needed — forwards the 3 incoming params).
 *  - AcknowledgeUIThread body → direct AcknowledgeSuccess(p1, p2, p3) + return-void
 *    (same Play-rejection failure mode for durables/subs). Smali:257 (.registers 7).
 *  - Verify(purchaseData, signature, key)Z → returnEarly(true) so the fabricated
 *    "morphe-signature" passes Java-side SHA1withRSA receipt verification if C#
 *    invokes it via JNI. Smali:1545 (.registers 6). Fingerprint transfers
 *    unchanged from ITD1 (T2, possible-patches.md).
 */
@Suppress("unused")
val intoTheDead2FreeIapPatch = bytecodePatch(
    name = "Full IAP Unlock",
    description = "Unlocks every purchase in the game for free. Tap buy and it is yours — no Google Play payment screen appears and you are never charged. Subscriptions like VIP membership show as pre-owned, so just open them, no purchase needed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD_2)

    execute {
        PurchaseUIThreadFingerprint.method.addInstructions(
            0, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.pikpok.dr2.play\",\"productId\":\""
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\",\"purchaseState\":0,\"purchaseToken\":\"morphe-token\",\"purchaseTime\":0,\"acknowledged\":false}"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            const-string v1, "morphe-signature"
            invoke-direct {p0, v0, v1}, Lcom/pikpok/AndroidStore;->PurchaseSuccess(Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent()
        )

        // T2a — Consume bypass: fake token reports success without reaching Play.
        ConsumeUIThreadFingerprint.method.addInstructions(
            0, """
            invoke-direct {p0, p1, p2, p3}, Lcom/pikpok/AndroidStore;->ConsumeSuccess(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent()
        )

        // T2b — Acknowledge bypass: same Play-rejection guard for durables/subs.
        AcknowledgeUIThreadFingerprint.method.addInstructions(
            0, """
            invoke-direct {p0, p1, p2, p3}, Lcom/pikpok/AndroidStore;->AcknowledgeSuccess(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """.trimIndent()
        )

        // T2c — Verify bypass: accept the fabricated signature.
        VerifyFingerprint.method.returnEarly(true)
    }
}

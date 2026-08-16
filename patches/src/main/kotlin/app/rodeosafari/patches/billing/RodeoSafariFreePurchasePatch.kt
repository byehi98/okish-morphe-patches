package app.rodeosafari.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.rodeosafari.patches.shared.Constants.COMPATIBILITY_RODEO

/**
 * EXPERIMENTAL — R1 free-purchase spoof. See risk summary at the bottom.
 *
 * Strategy: hook the single purchase funnel Yodo1Purchase.pay(Activity, productId, price, extra).
 * This is the ONLY method the Unity C# bridge (UnityYodo1Payment.purchase) calls when the player
 * taps a shop item, and its caller has ALREADY set UnityFlag$UnitySend object/method names — so a
 * synchronous success callback fired here is routed back to C# as the MAS "2001" purchase event.
 *
 * Instead of launching the real Google Play billing flow (Yodo1PayHelper.a(...)), we:
 *   1. Resolve the real product catalog entry via Yodo1ProductFactory.getProductData(p1).
 *   2. If the catalog isn't loaded yet (returns null), fall through to the real flow — safe fallback.
 *   3. Otherwise stamp an order: orderId = "morphe-<ts>", paytime = now, well-formed response JSON,
 *      extra passthrough — exactly the fields the real billing callback would have populated.
 *   4. Call Yodo1PayHelper.purchased(1, productData, "Purchase successful") directly — the public
 *      success sink the real adapter invokes. Inside it:
 *        - The 2001 listener dispatch happens FIRST (before analytics/verify), so Unity grants
 *          synchronously regardless of what happens after.
 *        - code==1 then runs Yodo1Analytics.trackIAPRevenue (try/catch-wrapped → safe) and an OPS
 *          order-verify POST with a NULL listener. The verify response handler (ops.payment.c$d)
 *          never stores that listener, so the server reply is silently discarded — no crash, no replay.
 *
 * Register budget: method has .registers 13 → locals v0-v7 are free; we use v0-v3 only
 * (p0-p4 remain untouched for the :cond_real fall-through).
 *
 * RISKS (documented, out of scope for R1):
 * - The actual economy/entitlement lives in native IL2CPP (libil2cpp.so) — NOT smali-reachable.
 *   Unity-side grant after 2001 is plausible but unverified; the C# layer may also re-validate
 *   against the Yodo1 OPS server, which will NOT have this fake order ("verifyOrder").
 * - verifyPurchases/inAppVerify/restoreProduct at startup re-queries real orders server-side;
 *   those stay untouched deliberately — stubbing them would fabricate a whole order list the
 *   server can't confirm. The R1 experiment is specifically the ACTIVE purchase path (2001).
 */
@Suppress("unused")
val rodeoSafariFreePurchasePatch = bytecodePatch(
    name = "Rodeo Stampede Free Purchase",
    description = "Spoofs in-app purchases as instantly successful at the Yodo1 purchase funnel.",
    default = true
) {
    compatibleWith(COMPATIBILITY_RODEO)

    execute {
        Yodo1PurchasePayFingerprint.method.addInstructionsWithLabels(0, """
            # p0=Activity, p1=productId, p2/p3=price, p4=extra (untouched)
            # v0=ProductData (may be null if catalog not loaded)
            invoke-static {}, Lcom/yodo1/android/sdk/helper/Yodo1ProductFactory;->getInstance()Lcom/yodo1/android/sdk/helper/Yodo1ProductFactory;
            move-result-object v0
            invoke-virtual {v0, p1}, Lcom/yodo1/android/sdk/helper/Yodo1ProductFactory;->getProductData(Ljava/lang/String;)Lcom/yodo1/android/sdk/constants/ProductData;
            move-result-object v0
            if-eqz v0, :cond_real
            # orderId = "morphe-" + System.currentTimeMillis()
            new-instance v1, Ljava/lang/StringBuilder;
            invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
            const-string v2, "morphe-"
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            move-result-object v1
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v2
            invoke-virtual {v1, v2, v3}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
            move-result-object v1
            invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0, v1}, Lcom/yodo1/sdk/adapter/entity/ChannelPayInfo;->setOrderId(Ljava/lang/String;)V
            # paytime = now
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v2
            invoke-virtual {v0, v2, v3}, Lcom/yodo1/sdk/adapter/entity/ChannelPayInfo;->setPaytime(J)V
            # well-formed purchase response JSON so UnityYodo1Payment${'$'}1 extra parsing never throws
            const-string v1, "{\"purchaseData\":\"{}\",\"signature\":\"morphe\",\"purchaseToken\":\"morphe\",\"channelOrderId\":\"morphe\"}"
            invoke-virtual {v0, v1}, Lcom/yodo1/sdk/adapter/entity/ChannelPayInfo;->setResponse(Ljava/lang/String;)V
            # extra passthrough from the original request
            invoke-virtual {v0, p4}, Lcom/yodo1/sdk/adapter/entity/ChannelPayInfo;->setExtra(Ljava/lang/String;)V
            # fire the public success sink — dispatches 2001 to the C# listener synchronously
            invoke-static {}, Lcom/yodo1/android/sdk/helper/Yodo1PayHelper;->getInstance()Lcom/yodo1/android/sdk/helper/Yodo1PayHelper;
            move-result-object v1
            const/4 v2, 0x1
            const-string v3, "Purchase successful"
            invoke-virtual {v1, v2, v0, v3}, Lcom/yodo1/android/sdk/helper/Yodo1PayHelper;->purchased(ILcom/yodo1/android/sdk/constants/ProductData;Ljava/lang/String;)V
            return-void
            :cond_real
            nop
        """.trimIndent())
    }
}

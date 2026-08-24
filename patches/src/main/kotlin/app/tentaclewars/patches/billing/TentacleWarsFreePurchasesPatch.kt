package app.tentaclewars.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.tentaclewars.patches.shared.Constants.COMPATIBILITY_TENTACLE_WARS

/**
 * Fake purchase / free in-app purchases.
 *
 * Strategy: body-replace Billing.buyProductImpl(Activity, productId): boolean, the single funnel
 * that would otherwise launch the Google Play purchase dialog. The replacement fires the game's own
 * purchase-grant callback (_onPurchaseUpdate — a Kotlin Function2 bridged to native
 * onPurchaseUpdate(String, Z) via MainActivity.onCreate$lambda$0) with (productId, true) and
 * returns true. Native then credits the SKU exactly as if a real purchase completed — no Play
 * dialog, no payment.
 *
 * Grants all three SKUs on tap:
 *   - tentacle_wars_vaccine_small_v3  (consumable currency)
 *   - tentacle_wars_vaccine_big_v3    (consumable currency)
 *   - tentacle_wars_remove_ads        (non-consumable entitlement)
 *
 * Faithful to app behavior: handlePurchase (Billing.smali:829–839) null-checks _onPurchaseUpdate
 * before every invoke and boxes the boolean via Boolean.valueOf — the injected body mirrors both,
 * so a Buy tap before callbacks are wired returns true without crashing (native simply receives no
 * grant; same worst case documented in the feasibility report).
 *
 * Register budget: method has .registers 7 → v0–v3 locals are free; we use v0/v1 only
 * (p0=this Billing, p1=Activity, p2=productId remain untouched). Everything after the inserted
 * return is unreachable dead code left in place deliberately (standard short-circuit pattern).
 *
 * Note: genuine purchases still restore normally at startup via queryPurchases() — untouched.
 */
@Suppress("unused")
val tentacleWarsFreePurchasesPatch = bytecodePatch(
    name = "Tentacle Wars Free Purchases",
    description = "Spoofs in-app purchases as instantly successful: tapping Buy grants the SKU " +
        "directly through the game's own purchase callback — no Google Play dialog, no payment. " +
        "For ad-free, just tap the Remove-Ads button on the main game screen.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TENTACLE_WARS)

    execute {
        BillingBuyProductImplFingerprint.method.addInstructionsWithLabels(0, """
            # p0=this(Billing), p1=Activity (unused), p2=productId
            # v0=_onPurchaseUpdate callback (nullable — guard mirrors handlePurchase)
            iget-object v0, p0, Lcom/tw/Billing;->_onPurchaseUpdate:Lkotlin/jvm/functions/Function2;
            if-eqz v0, :cond_no_callback
            # invoke callback(productId, true) — box the boolean like the original does
            const/4 v1, 0x1
            invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v1
            invoke-interface {v0, p2, v1}, Lkotlin/jvm/functions/Function2;->invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            :cond_no_callback
            const/4 v0, 0x1
            return v0
        """.trimIndent())
    }
}

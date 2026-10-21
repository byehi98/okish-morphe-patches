package app.vitamahjong.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable
import app.vitamahjong.patches.shared.Constants.COMPATIBILITY_VITA_MAHJONG

/**
 * Vita Mahjong — Restore Purchases (3.43.0 / versionCode 1917).
 *
 * `PurchaseModuleBridge.queryAllPurchases()V` normally posts a Runnable that asks
 * Play Billing for owned purchases and forwards them to Unity. On a fresh
 * sideloaded install Play returns an empty list, so the no-ads/VIP entitlement
 * looks unowned.
 *
 * This patch replaces the body with a synthetic owned-purchase list:
 *
 *   - `m_ads_remove`  (non-consumable no-ads purchase, verified product id)
 *   - `sub_1_year_vm` (longest subscription, verified product id)
 *
 * Each `PurchaseData` gets stable synthetic identity values:
 *   token `morphe-token`, order ids `GPA.morphe.no_ads` / `GPA.morphe.vip`,
 *   inner order ids `morphe-inner-no-ads` / `morphe-inner-vip`, and
 *   `purchaseTime = System.currentTimeMillis()`.
 *
 * The list is handed to the real `PurchaseModuleBridge$2.onSuccess(List)`, which
 * serializes it and sends `OnQueryAllPurchases` to Unity (`PurchaseInitMessageHandler`).
 *
 * REGISTER EXPANSION:
 * The shipped method is `.registers 2` (static, no params). The replacement needs
 * `v0` (ArrayList), `v1` (PurchaseData) and the `v2/v3` wide pair for
 * `currentTimeMillis`, so the method is cloned with `additionalRegisters = 8`
 * (→ `.registers 10`) and swapped into the class, following the same pattern as
 * `app/deadtrigger/patches/billing/FreeStorePatch.kt` and
 * `app/shootyskies/patches/ads/ShootySkiesAdFreePatch.kt`. `queryAllPurchases`
 * has no exception table, and the injected block is straight-line
 * (`addInstructions`, no labels).
 */
@Suppress("unused")
val vitaMahjongRestorePurchasesPatch = bytecodePatch(
    name = "Vita Mahjong Restore Purchases",
    description = "On a fresh install, \"Restore Purchases\" reports the no-ads pack and the yearly VIP subscription as already owned, so those entitlements activate without a real Google Play purchase.",
    default = true
) {
    compatibleWith(COMPATIBILITY_VITA_MAHJONG)

    execute {
        val restoreMethod = RestorePurchasesFingerprint.method
        // .registers 2 → +8 → .registers 10 (locals v0-v9, no params).
        val restoreExpanded = restoreMethod.cloneMutable(additionalRegisters = 8)
        mutableClassDefBy(restoreMethod.definingClass).methods.apply {
            remove(restoreMethod)
            add(restoreExpanded)
        }

        restoreExpanded.addInstructions(0, """
            # ── v0 = list of synthetic owned purchases ──
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V

            # ══ m_ads_remove (no-ads entitlement) ══
            new-instance v1, Lcom/learnings/purchase/PurchaseData;
            invoke-direct {v1}, Lcom/learnings/purchase/PurchaseData;-><init>()V

            const-string v2, "m_ads_remove"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setProductId(Ljava/lang/String;)V

            const-string v2, "morphe-token"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setToken(Ljava/lang/String;)V

            const-string v2, "GPA.morphe.no_ads"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setOrderId(Ljava/lang/String;)V

            const-string v2, "morphe-inner-no-ads"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setInnerOrderId(Ljava/lang/String;)V

            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v2
            invoke-virtual {v1, v2, v3}, Lcom/learnings/purchase/PurchaseData;->setPurchaseTime(J)V

            invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

            # ══ sub_1_year_vm (yearly VIP subscription) ══
            new-instance v1, Lcom/learnings/purchase/PurchaseData;
            invoke-direct {v1}, Lcom/learnings/purchase/PurchaseData;-><init>()V

            const-string v2, "sub_1_year_vm"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setProductId(Ljava/lang/String;)V

            const-string v2, "morphe-token"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setToken(Ljava/lang/String;)V

            const-string v2, "GPA.morphe.vip"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setOrderId(Ljava/lang/String;)V

            const-string v2, "morphe-inner-vip"
            invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setInnerOrderId(Ljava/lang/String;)V

            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v2
            invoke-virtual {v1, v2, v3}, Lcom/learnings/purchase/PurchaseData;->setPurchaseTime(J)V

            invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

            # ── deliver OnQueryAllPurchases(_list) to Unity ──
            new-instance v1, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;
            invoke-direct {v1}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;-><init>()V
            invoke-virtual {v1, v0}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;->onSuccess(Ljava/util/List;)V

            return-void
        """.trimIndent())
    }
}

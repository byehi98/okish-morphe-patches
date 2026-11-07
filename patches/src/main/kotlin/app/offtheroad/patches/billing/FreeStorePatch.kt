package app.offtheroad.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.offtheroad.patches.shared.Constants.COMPATIBILITY_OFF_THE_ROAD

/**
 * Off The Road 1.18.2 · **Free Store** — every shop product grants instantly.
 *
 * cocos2d-x + custom `xGen` engine (`com.dogbytegames.offtheroad`). **DEX-only — no
 * native edit and no Play Billing involvement.**
 *
 * `InappPurchase.startPurchase(String)` is the single Java method behind the whole
 * store. Native `cInappPurchase::startPurchase` (`libgame.so` `0x8ae408`) looks the
 * method id up once and calls it with the SKU, so replacing the body intercepts:
 *
 * ```
 * coinpack1..5, ticketpack1..3, nitropack, repairpack   → consumables (repeatable)
 * removeads, inventory_yacht                           → entitlements
 * vip_club                                             → subscription (type 2)
 * offer_*                                              → seasonal bundles
 * 84 vehicle SKUs (table @ 0x8d8190, stride 0x18)       → cars
 * ```
 *
 * The replacement calls the **public native** `purchaseCompleteCallback(String)`,
 * which is the exact callback a real Google Play purchase ends with:
 *
 * ```
 * purchaseCompleteCallback  (JNI thunk 0x8ad9ec)
 *   └─ raiseOnMainThread<sInappPurchaseResult>  (0x8d3e00)
 *        └─ cApplication::onItemPurchasedOrRestored  (0x375e48)
 *             → applies coins/tickets/VIP/cars, writes the game's own save,
 *               sets cApplication+0x215, hides the progress indicator
 * ```
 *
 * There is no receipt, token, price or signature check in that funnel, and the save
 * writes are the game's own — the grant persists across restarts exactly like a real
 * purchase.
 *
 * Register safety: `startPurchase` is `.registers 5` (`p0 = v4`, `p1 = v3`); the
 * method's try range begins at `:try_start_33`, so injecting at index 0 is outside
 * every handler. Only `p0`/`p1` are read.
 *
 * Interaction: buying `removeads` through this patch sets `cApplication+0x54`, which
 * disables rewarded ads too. Keep this patch and "Free Rewarded Ads" independent.
 */
@Suppress("unused")
val offTheRoadFreeStorePatch = bytecodePatch(
    name = "Free Store",
    description = "Everything in the shop is free. Tap any item — coins, tickets, VIP, " +
        "remove-ads, car packs or bundles — and it is granted immediately, with no Google " +
        "Play payment screen and nothing charged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_OFF_THE_ROAD)

    execute {
        StartPurchaseFingerprint.method.addInstructions(
            0,
            """
            # p0 = InappPurchase instance, p1 = SKU from native
            # Native grant funnel, including save writes and progress-indicator cleanup.
            invoke-virtual {p0, p1}, Lcom/utils/InappPurchase;->purchaseCompleteCallback(Ljava/lang/String;)V
            return-void
            """.trimIndent(),
        )
    }
}

package app.offtheroad.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.offtheroad.patches.billing.GetProductPurchaseTimeFingerprint
import app.offtheroad.patches.billing.SetNativeInitFingerprint
import app.offtheroad.patches.shared.Constants.COMPATIBILITY_OFF_THE_ROAD

private const val REWARD_BRIDGE = "Lapp/morphe/extension/offtheroad/RewardBridge;"

/**
 * Off The Road 1.18.2 · **VIP Club Unlocked** — the VIP subscription is granted at
 * launch without opening the shop.
 *
 * Pure DEX plus the `offtheroad` extension.
 *
 * ## How VIP state is stored
 *
 * `cApplication::isVipClubActive()` (libgame.so `0x4505e0`) is a one-liner:
 * `return *(void**)(cApplication + 0x478) != nullptr`. Every VIP gate in the game
 * (garage button, VIP window, portal/yacht rewards, driveable offer, …) calls it.
 *
 * That timestamp is **not** written by `onItemPurchasedOrRestored`. The `vip_club`
 * branch there (`0x376768`) only sets `cApplication+0x480 = 1` (the "show thank-you"
 * flag) and calls `cInappPurchase::requestProductData(true)`. The real value comes
 * from the handler of the `cInappPurchase+0x60` event (`0x45ce7c`), which is raised
 * by the native `requestProductPurchasesCompleteCallback(true)` worker
 * (`0x8af328`). That worker copies every product's purchase time from Java via
 * `InappPurchase.getProductPurchaseTime(sku)` and then raises the event, whose
 * handler computes and stores `cApplication+0x478`.
 *
 * This is why simply calling `purchaseCompleteCallback("…vip_club")` does not
 * unlock VIP: the follow-up Play query either never completes (no Play services)
 * or completes with an empty purchase list, and the Java table is zeroed by
 * `clearPurchaseTimes()`, so the native handler sees a purchase time of 0 and
 * clears the VIP state again.
 *
 * ## Patch
 *
 * 1. `getProductPurchaseTime` is patched to report the current unix time for the
 *    VIP SKU (all other SKUs keep the original behaviour, so vehicle/currency
 *    restore logic is untouched).
 * 2. `setNativeInit()` (called once by the native `cInappPurchase` constructor)
 *    triggers `RewardBridge.grantVipDelayed()`, which ~1.5 s later invokes the
 *    public native `requestProductPurchasesCompleteCallback(true)` directly.
 *    That runs the exact same refresh the Play-query path would run, but without
 *    Play, a purchase, a token or a receipt.
 *
 * ```
 * setNativeInit()
 *   → grantVipDelayed()
 *     → (main handler, ~1.5 s later)
 *       → requestProductPurchasesCompleteCallback(true)
 *         → native worker reads getProductPurchaseTime("…vip_club")  == now
 *           → cInappPurchase+0x60 event
 *             → cApplication handler 0x45ce7c
 *               → cApplication+0x478 = vip timestamp   ← VIP active
 * ```
 *
 * **Why 1.5 s.** `setNativeInit` runs inside the `cInappPurchase` constructor,
 * before `cApplication::initPurchase` has registered the `+0x60` handler. The
 * deferred call lands after `initPurchase` returned and the custom config/profile
 * load has finished, so the value is not overwritten during startup.
 *
 * The store id uses the short prefix `com.dogbyte.offtheroad.*` (not the Android
 * package id `com.dogbytegames.offtheroad`); the native classifier only matches the
 * short form.
 *
 * Independent of "Free Store": that patch grants VIP only after the user taps the
 * VIP offer, this one grants it on every launch.
 */
@Suppress("unused")
val offTheRoadVipClubUnlockPatch = bytecodePatch(
    name = "VIP Club Unlocked",
    description = "Unlocks the VIP Club subscription automatically on every launch. " +
        "No payment screen, no Google Play subscription, nothing charged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_OFF_THE_ROAD)

    extendWith("extensions/offtheroad.mpe")

    execute {
        // Report a live purchase timestamp for the VIP SKU only. The native
        // refresh worker copies this value into the product record and the
        // +0x60 event handler turns it into cApplication+0x478 (VIP active).
        // .registers 6 → four locals (v0..v3); p0 = v4, p1 = v5. The injected
        // block stays on v0..v3 so `this` (v4) is never clobbered.
        GetProductPurchaseTimeFingerprint.method.addInstructions(
            0,
            """
            const-string v0, "com.dogbyte.offtheroad.vip_club"
            invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :offtheroad_getpurchase_not_vip

            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v0
            const-wide/16 v2, 0x3e8
            div-long/2addr v0, v2
            return-wide v0

            :offtheroad_getpurchase_not_vip
            """.trimIndent(),
        )

        SetNativeInitFingerprint.method.addInstructions(
            0,
            """
            # setNativeInit is .registers 2 and needs no locals here; the bridge
            # posts the synthetic refresh on the main looper after initPurchase()
            # has finished registering the purchase event handlers.
            invoke-static {}, $REWARD_BRIDGE->grantVipDelayed()V
            """.trimIndent(),
        )
    }
}

package app.swampattack.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.swampattack.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK

/**
 * Swamp Attack 4.8.7.0 · **Free Store** — every purchase grants instantly, free.
 *
 * SDL2 + Marmalade s3e + Outfit7 native engine (`liblibO7.so`); **DEX-only, no native
 * edit.** The game package `com.libo7.swampattack.*` is unobfuscated.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `NativeInterface.startPurchasing(String)` — smali `NativeInterface.smali:2446`,
 * `.registers 9`, `public final`, no exception table — has its body replaced. It is
 * the ONLY funnel every buy button reaches:
 *
 * ```
 * native GS_PurchaseProduct (liblibO7.so)
 *   → libO7.libO7_IAP_Buy(String)                 libO7.smali:1431 (single call site)
 *     → NativeInterface.startPurchasing(String)   ← replaced below
 *       → BillingManager.purchase$default → Play Billing / Yandex Pay
 *         → validateIAP cloud fn → sendSuccessToGameAfterValidation
 *             JSON {transactionID, productId, purchaseToken, restored}
 *             → NativeInterface.markUserPurchase()
 *             → libO7.native_libO7_IAP_SendEventToApp(0x3f, json)
 * ```
 *
 * The replacement reproduces the **tail** of that chain through the app's own
 * production-granted shortcut, `BillingManager.grantPurchaseAdjustment(productId,
 * transactionId)` (smali `BillingManager.smali:7209`, `public final`, `.registers 8`):
 *
 * * builds the exact same JSON payload `{transactionID, productId,
 *   purchaseToken:"", restored:true}` and sends `LIBO7_EVENT_IAP_PurchaseSuccessful`
 *   (`0x3f`) straight to the native engine — the same event code and payload shape
 *   `sendSuccessToGameAfterValidation` emits after a real validated purchase;
 * * **skips Play Billing AND the server-side receipt validation entirely**
 *   (`https://us-central1-swamp-attack-1.cloudfunctions.net/validateIAP` is never
 *   reached, so there is no token to forge and nothing server-side to defeat);
 * * is the same path the developer's own Firebase Remote Config support tooling uses
 *   (`FirebaseRemoteConfigManager.onPurchaseAdjustment`, jadx line 558, keys
 *   `iap_adjustment` / `iap_adjustment_id` / `iap_adjustment_transaction_id`) — so it
 *   is exercised production code, not a guessed-forge of `BillingClient`;
 * * lives **before** the Google/Yandex regional routing decision, so it covers both
 *   billing backends with one hook.
 *
 * `markUserPurchase()` (smali `NativeInterface.smali:1754`) is called first because
 * `grantPurchaseAdjustment` does NOT flip the paid-user flag, while the real validated
 * path always does (`sendSuccessToGameAfterValidation` calls it at smali 6066 before
 * sending event `0x3f`). It writes SharedPreferences `prefs` / `PaidUser.isPaidUser=true`,
 * the flag native reads back through `NativeInterface.isPaidUser()` (with the legacy
 * `FelisBillingCore` migration fallback) to treat the player as having purchased.
 *
 * A **unique transactionId** is built per tap (`morphe.<productId><millis>`) because the
 * native engine keys grant bookkeeping on it — a constant id could be deduped on the
 * second grant of the same SKU. Both SKUs (`...double_coins`, `...more_gifts`) are
 * non-consumable (`BillingManager.NON_CONSUMABLE_PRODUCT_IDS`, smali `<clinit>` 306),
 * and native persists grants in its own save file (`Purchase.dat`), so unlocks survive
 * restarts without any Java-side state.
 *
 * ============================================================================
 * REGISTER BUDGET & INJECTION SAFETY
 * ============================================================================
 * `.registers 9` with two params → locals `v0…v6`, `p0 = v7` (this), `p1 = v8`
 * (purchaseId). The injected block uses `v0…v3` only (v2/v3 for the `J` millis),
 * all inside the 4-bit `35c` range, and `p0`/`p1` are only read. The method has NO
 * try/catch table, so morphe's instruction-tree writer merges the labeled-free block
 * cleanly; the original `purchase$default` body remains below the injected
 * `return-void` as unreachable dead code — the standard short-circuit shape.
 *
 * Threading: `startPurchasing` is invoked from the native s3e bridge on the game's own
 * thread — the same context from which the real billing flow was launched.
 * `markUserPurchase` uses `SharedPreferences.apply()` (any thread) and
 * `grantPurchaseAdjustment` is a JSON build + JNI call, which the remote-config path
 * already invokes from the main handler. No extra queueing is added.
 *
 * ============================================================================
 * CAVEATS
 * ============================================================================
 * * "Any purchase removes all forced ads" is decided NATIVE-side: permanent when remote
 *   config `timed_ad_removal_on_purchase` is off, **7 days** when it is on (native
 *   strings: "Any purchase removes all forced ads for 7 days!"). For guaranteed
 *   permanent ad-free, also enable the companion **Remove Ads** patch, which forces the
 *   Java-side gate `AdManager.areAdsPermanentlyRemoved` — that gate alone controls
 *   interstitial load/show, independent of native's timer.
 * * Consumable-style repeat taps are fine: each tap sends a fresh transactionId; rows
 *   the game already considers owned are greyed out by the game's own store UI.
 * * Do NOT move this hook into `GooglePlayBillingProvider.purchase` — the downstream
 *   `waitForServerValidation` would reject any forged token at the `validateIAP` cloud
 *   function (`onServerValidationFailed` → no grant). The whole point is to never
 *   enter that flow.
 * * Do NOT stub `grantPurchaseAdjustment` itself or `libO7_IAP_Buy` — those are the
 *   grant/entry plumbing; stubbing them deletes the pipeline.
 *
 * Anchors: analysis/swampattack/notes/iap-analysis.md (funnel diagram + smali lines),
 * analysis/swampattack/possible-patches-freebuff.md (feasibility table).
 *
 * Disjoint from `../ads/AdRemovalPatch.kt`: different classes, no shared offsets,
 * enabling both is safe and order-independent.
 */
@Suppress("unused")
val swampAttackFreeStorePatch = bytecodePatch(
    name = "Free Store",
    description = "Everything in the shop is granted instantly and free — double coins, " +
        "more gifts and the any-purchase perks — with no Google Play payment screen, " +
        "no account and nothing charged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK)

    execute {
        StartPurchasingFingerprint.method.addInstructions(0, """
            # p0 = NativeInterface instance, p1 = purchaseId (productId from native)
            # .registers 9 → v0..v6 locals, p0 = v7, p1 = v8

            # 1) Flip the paid-user flag exactly like the validated-purchase path does
            #    (sendSuccessToGameAfterValidation calls markUserPurchase() before event 0x3f).
            invoke-virtual {p0}, Lcom/libo7/swampattack/NativeInterface;->markUserPurchase()V

            # 2) BillingManager instance via the app's own accessor
            #    (same sequence FirebaseRemoteConfigManager.onPurchaseAdjustment uses).
            sget-object v0, Lcom/libo7/swampattack/billing/BillingManager;->Companion:Lcom/libo7/swampattack/billing/BillingManager${'$'}Companion;
            invoke-virtual {v0}, Lcom/libo7/swampattack/billing/BillingManager${'$'}Companion;->getInstance()Lcom/libo7/swampattack/billing/BillingManager;
            move-result-object v0

            # 3) Unique transactionId: "morphe." + productId + currentTimeMillis
            new-instance v1, Ljava/lang/StringBuilder;
            invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
            const-string v2, "morphe."
            invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v2
            invoke-virtual {v1, v2, v3}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
            invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v1

            # 4) Grant through the app's production adjustment path → native event 0x3f.
            #    Skips Play Billing AND the validateIAP cloud function entirely.
            invoke-virtual {v0, p1, v1}, Lcom/libo7/swampattack/billing/BillingManager;->grantPurchaseAdjustment(Ljava/lang/String;Ljava/lang/String;)V

            return-void
            nop
        """.trimIndent())
    }
}

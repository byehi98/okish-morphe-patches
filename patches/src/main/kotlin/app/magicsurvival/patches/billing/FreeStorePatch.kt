package app.magicsurvival.patches.billing

import app.magicsurvival.patches.shared.Constants.COMPATIBILITY_MAGIC_SURVIVAL
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

/**
 * Magic Survival — Free Store / Fake Purchase (v0.9941, Google Play Billing 8.3.0).
 *
 * ============================================================
 * HOW THE PURCHASE PATH LOOKS IN THIS APP
 * ============================================================
 * The game is Unity 2022.3 IL2CPP: no purchase logic exists in Java — the C#
 * side talks straight to `com.android.billingclient.api.*` over JNI
 * (AndroidJavaObject). `global-metadata.dat` carries the JNI method names
 * (`startConnection`, `isReady`, `launchBillingFlow`, `queryProductDetailsAsync`,
 * `queryPurchasesAsync`, `onPurchasesUpdated`, `acknowledgePurchase`,
 * `consumeAsync`) but there is NO Unity IAP Java glue and no Java caller of
 * launchBillingFlow outside the library itself.
 *
 * The listener the game registers through `BillingClient.newBuilder()` ends up
 * in `BillingClientImpl.zzf` (an `zzab`), whose `zzf()` accessor returns the
 * `PurchasesUpdatedListener` — for a Unity build that listener is an
 * AndroidJavaProxy bridging back into C#. So the entire "did the user pay?"
 * signal the game ever sees is one `onPurchasesUpdated(BillingResult, List)`
 * call. Nothing verifies receipts on Android (no `Verify`, no Play Integrity,
 * no signature check in the DEX), so a purchase fabricated at that layer is
 * processed exactly like a real one.
 *
 * Catalog (from `data.unity3d`): `remove_ad` (non-consumable),
 * `gem_10` / `gem_16` / `gem_80` / `gem_160` (consumables).
 *
 * ============================================================
 * THE SIX HOOKS
 * ============================================================
 * 1. `launchBillingFlow` — resolve the REAL tapped productId from
 *    `BillingFlowParams` (ProductDetailsParams via `zzk()`, SkuDetails via
 *    `zzj()`), persist it in SharedPreferences, build a genuine `Purchase`
 *    from a fabricated receipt JSON and deliver it to the game's
 *    PurchasesUpdatedListener, then return an OK BillingResult. No Play
 *    dialog, no payment. `zzcw.launchBillingFlow` invokes super INLINE on the
 *    caller's thread (zzcw.java:249), so the C# proxy runs on the Unity main
 *    thread — exactly where the real callback would have landed.
 *
 * 2. `queryPurchasesAsync` — the restore path Unity IAP hits on every launch.
 *    It replays the persisted grant tokens (`SharedPreferences` file
 *    `morphe_iap`, key `owned`), rebuilding a `Purchase` for every entry, so
 *    `remove_ad` and any un-consumed grant survive a restart. Because the
 *    original token is replayed verbatim, a restore that races an unconsumed
 *    purchase is de-duplicated by Unity instead of granting twice.
 *
 * 3. `consumeAsync` — drops the consumed token from the persisted set before
 *    reporting success, so consumables are granted once instead of on every
 *    launch.
 *
 * 4. `acknowledgePurchase` — reports OK without asking Google (Google has never
 *    heard of these purchases, and the JSON ships `acknowledged:true` anyway —
 *    this is belt and braces for a C# side that acknowledges unconditionally).
 *
 * 5. `zzcw.queryProductDetailsAsync` + `BillingClientImpl.queryProductDetailsAsync`
 *    — the offline store. Unity IAP populates the shop from
 *    `queryProductDetailsAsync`; with no network the Play Store answers
 *    SERVICE_UNAVAILABLE, store initialization fails and the shop never opens.
 *    Both entry points now build `ProductDetails` locally for exactly the
 *    product ids the game requested, so the catalog resolves offline.
 *
 * 6. `zzcw.consumeAsync` / `zzcw.acknowledgePurchase` are additionally re-routed
 *    straight to super: the stock wrappers run those calls on the client's
 *    ExecutorService (zzcw.java:208/225), which would push our fabricated
 *    callbacks into the C# AndroidJavaProxy from a background thread.
 *
 * ============================================================
 * WHY GEMS USED TO WORK ONLY ONCE
 * ============================================================
 * Unity IAP guards purchases with a transaction log (`ProcessPurchaseIfNew` /
 * `HasRecordedTransaction` in the IL2CPP metadata) keyed by the purchase token.
 * The old patch used a constant `morphe-<productId>` token, so the first
 * purchase was recorded and every later purchase of the same SKU was silently
 * dropped as a duplicate. Tokens (and order ids) are now unique per purchase:
 * `morphe-<productId>|<uuid>`, persisted so the restore path replays the exact
 * same token.
 *
 * The Java helpers live in the `magicsurvival` extension (`FakeBilling`):
 * unique token/order id generation, receipt JSON, ProductDetails JSON,
 * reflection into the obfuscated billing classes, and the persisted set.
 *
 * ============================================================
 * REGISTER BUDGETS — verified against 0.9941 smali
 * ============================================================
 *  BillingClientImpl.launchBillingFlow   .registers 30 → v0..v12 used (p0=v27, p1=v28,
 *      p2=v29). invoke-* (35c) and iget/iput (22c) only encode 4-bit registers,
 *      so p0/p2 are copied to v12/v0 with move-object/from16 first and NO
 *      injected instruction ever references a register above v15.
 *  BillingClientImpl.queryPurchasesAsync .registers 10 → v0..v6  (p0..p2)
 *  BillingClientImpl.consumeAsync        .registers 9  → v0..v5
 *  BillingClientImpl.acknowledgePurchase .registers 9  → v0..v2
 *  BillingClientImpl.queryProductDetailsAsync .registers 9 → v0..v1
 *  zzcw.queryProductDetailsAsync         .registers 5  → v0, v1
 *  zzcw.consumeAsync / acknowledgePurchase .registers 5 → p0, p1, p2 only
 */
private const val FAKE_BILLING = "Lapp/morphe/extension/magicsurvival/FakeBilling;"

@Suppress("unused")
val magicSurvivalFreeStorePatch = bytecodePatch(
    name = "Magic Survival Free Store",
    description = "Grants every in-app purchase for free: tapping Buy fabricates a successful " +
        "Google Play purchase (remove-ad, gem packs) and feeds it through the billing listener " +
        "without opening the payment dialog. Purchases can be repeated any number of times, " +
        "granted entitlements are restored on every launch, and the store catalog is answered " +
        "locally so the shop also works without an internet connection.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MAGIC_SURVIVAL)

    extendWith("extensions/magicsurvival.mpe")

    execute {
        // ==========================================================
        // 1 — Fake purchase: replace launchBillingFlow's entry.
        // ==========================================================
        BillingLaunchBillingFlowFingerprint.method.addInstructionsWithLabels(
            0, """
            # p0 = BillingClientImpl (v27), p1 = Activity (v28), p2 = BillingFlowParams (v29).
            # .registers 30, but every plain invoke-* format only encodes 4-bit
            # registers (v0..v15), so p2/p0 are copied into v0/v12 first and the
            # whole block stays inside v0..v12 (iget-object is 22c: its object
            # operand is 4-bit too, so p0 itself cannot be dereferenced here).
            move-object/from16 v0, p2
            move-object/from16 v12, p0

            # ---- resolve the REAL productId into v1 (null if it cannot be read) ----
            const/4 v1, 0x0

            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
            move-result-object v2
            if-eqz v2, :sku_path
            invoke-interface {v2}, Ljava/util/List;->isEmpty()Z
            move-result v3
            if-nez v3, :sku_path
            const/4 v3, 0x0
            invoke-interface {v2, v3}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Lcom/android/billingclient/api/BillingFlowParams${'$'}ProductDetailsParams;
            invoke-virtual {v2}, Lcom/android/billingclient/api/BillingFlowParams${'$'}ProductDetailsParams;->zza()Lcom/android/billingclient/api/ProductDetails;
            move-result-object v2
            if-eqz v2, :sku_path
            invoke-virtual {v2}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
            move-result-object v1

            :sku_path
            if-nez v1, :have_id
            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
            move-result-object v2
            if-eqz v2, :have_id
            check-cast v2, Ljava/util/List;
            invoke-interface {v2}, Ljava/util/List;->isEmpty()Z
            move-result v3
            if-nez v3, :have_id
            const/4 v3, 0x0
            invoke-interface {v2, v3}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Lcom/android/billingclient/api/SkuDetails;
            invoke-virtual {v2}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
            move-result-object v1

            :have_id
            if-eqz v1, :no_product

            # ---- build + persist the fabricated Purchase (unique token/order id) ----
            iget-object v9, v12, Lcom/android/billingclient/api/BillingClientImpl;->zzg:Landroid/content/Context;
            invoke-static {v9, v1}, $FAKE_BILLING->purchase(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;
            move-result-object v5
            check-cast v5, Lcom/android/billingclient/api/Purchase;

            new-instance v6, Ljava/util/ArrayList;
            invoke-direct {v6}, Ljava/util/ArrayList;-><init>()V
            invoke-virtual {v6, v5}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

            # ---- OK BillingResult ----
            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v7
            check-cast v7, Lcom/android/billingclient/api/BillingResult;

            # ---- deliver it to the game, exactly like a real Play purchase ----
            iget-object v8, v12, Lcom/android/billingclient/api/BillingClientImpl;->zzf:Lcom/android/billingclient/api/zzab;
            if-eqz v8, :no_listener
            invoke-virtual {v8}, Lcom/android/billingclient/api/zzab;->zzf()Lcom/android/billingclient/api/PurchasesUpdatedListener;
            move-result-object v8
            if-eqz v8, :no_listener
            invoke-interface {v8, v7, v6}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V

            :no_listener
            return-object v7

            :no_product
            # product id unreadable — answer OK and leave the real flow (dead below) alone
            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Lcom/android/billingclient/api/BillingResult;
            return-object v2
            """.trimIndent()
        )

        // ==========================================================
        // 2 — Restore: rebuild queryPurchasesAsync's answer from our set.
        // ==========================================================
        BillingQueryPurchasesAsyncFingerprint.method.addInstructionsWithLabels(
            0, """
            # p0 = BillingClientImpl, p1 = QueryPurchasesParams, p2 = PurchasesResponseListener
            iget-object v0, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzg:Landroid/content/Context;

            # persisted tokens are replayed verbatim (stable restore token for legacy entries)
            invoke-static {v0}, $FAKE_BILLING->restorePurchases(Landroid/content/Context;)Ljava/util/List;
            move-result-object v2

            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lcom/android/billingclient/api/BillingResult;
            invoke-interface {p2, v0, v2}, Lcom/android/billingclient/api/PurchasesResponseListener;->onQueryPurchasesResponse(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 3 — Consume: drop the consumed token from the persisted set, report OK.
        // ==========================================================
        BillingConsumeAsyncFingerprint.method.addInstructionsWithLabels(
            0, """
            # p0 = BillingClientImpl, p1 = ConsumeParams, p2 = ConsumeResponseListener
            invoke-virtual {p1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :build_result

            # only tokens minted by this patch are dropped ("morphe-<productId>|<unique>")
            iget-object v1, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzg:Landroid/content/Context;
            invoke-static {v1, v0}, $FAKE_BILLING->remove(Landroid/content/Context;Ljava/lang/String;)V

            :build_result
            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Lcom/android/billingclient/api/BillingResult;
            invoke-interface {p2, v2, v0}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 4 — Acknowledge: answer OK locally.
        // ==========================================================
        BillingAcknowledgePurchaseFingerprint.method.addInstructions(
            0, """
            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Lcom/android/billingclient/api/BillingResult;
            invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 5 — Offline catalog: answer queryProductDetailsAsync locally.
        //     zzcw is the runtime instance (BillingClient$Builder.build),
        //     so patching it answers on the caller's thread and skips the
        //     executor hop; BillingClientImpl is patched as a safety net.
        // ==========================================================
        ZzcwQueryProductDetailsAsyncFingerprint.method.addInstructions(
            0, """
            # p0 = zzcw, p1 = QueryProductDetailsParams, p2 = ProductDetailsResponseListener
            invoke-static {p1}, $FAKE_BILLING->buildQueryResult(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v1
            check-cast v0, Lcom/android/billingclient/api/QueryProductDetailsResult;
            check-cast v1, Lcom/android/billingclient/api/BillingResult;
            invoke-interface {p2, v1, v0}, Lcom/android/billingclient/api/ProductDetailsResponseListener;->onProductDetailsResponse(Lcom/android/billingclient/api/BillingResult;Lcom/android/billingclient/api/QueryProductDetailsResult;)V
            return-void
            """.trimIndent()
        )
        BillingQueryProductDetailsAsyncFingerprint.method.addInstructions(
            0, """
            # p0 = BillingClientImpl, p1 = QueryProductDetailsParams, p2 = ProductDetailsResponseListener
            invoke-static {p1}, $FAKE_BILLING->buildQueryResult(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            invoke-static {}, $FAKE_BILLING->ok()Ljava/lang/Object;
            move-result-object v1
            check-cast v0, Lcom/android/billingclient/api/QueryProductDetailsResult;
            check-cast v1, Lcom/android/billingclient/api/BillingResult;
            invoke-interface {p2, v1, v0}, Lcom/android/billingclient/api/ProductDetailsResponseListener;->onProductDetailsResponse(Lcom/android/billingclient/api/BillingResult;Lcom/android/billingclient/api/QueryProductDetailsResult;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 6 — Keep the two async wrappers on the caller's thread.
        // ==========================================================
        ZzcwConsumeAsyncFingerprint.method.addInstructions(
            0, """
            invoke-super {p0, p1, p2}, Lcom/android/billingclient/api/BillingClientImpl;->consumeAsync(Lcom/android/billingclient/api/ConsumeParams;Lcom/android/billingclient/api/ConsumeResponseListener;)V
            return-void
            """.trimIndent()
        )
        ZzcwAcknowledgePurchaseFingerprint.method.addInstructions(
            0, """
            invoke-super {p0, p1, p2}, Lcom/android/billingclient/api/BillingClientImpl;->acknowledgePurchase(Lcom/android/billingclient/api/AcknowledgePurchaseParams;Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;)V
            return-void
            """.trimIndent()
        )
    }
}

package app.magicsurvival.patches.billing

import app.magicsurvival.patches.shared.Constants.COMPATIBILITY_MAGIC_SURVIVAL
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
 * (`launchBillingFlow`, `queryPurchasesAsync`, `onPurchasesUpdated`,
 * `acknowledgePurchase`, `consumeAsync`) but there is NO Unity IAP Java glue
 * and no Java caller of launchBillingFlow outside the library itself.
 *
 * The listener the game registers through `BillingClient.newBuilder()` ends up
 * in `BillingClientImpl.zzf` (an `zzab`), whose `zzf()` accessor returns the
 * `PurchasesUpdatedListener` — for a Unity build that listener is an
 * AndroidJavaProxy bridging back into C#. So the entire "did the user pay?"
 * signal the game ever sees is one `onPurchasesUpdated(BillingResult, List)`
 * call. Nothing verifies receipts on Android (no `Verify`, no Play Integrity,
 * no signature check in the DEX), so a purchase fabricated at that layer is
 * processed exactly like a real one: `ProcessPurchase`-equivalent C# logic
 * grants the SKU and, for `remove_ad`, flips the ad entitlement.
 *
 * Catalog (from `data.unity3d`): `remove_ad` (non-consumable),
 * `gem_10` / `gem_16` / `gem_80` / `gem_160` (consumables).
 *
 * ============================================================
 * THE FOUR HOOKS
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
 *    It now answers from our persisted set (`SharedPreferences` file
 *    `morphe_iap`, key `owned`), rebuilding a `Purchase` for every granted id,
 *    so `remove_ad` and any un-consumed grant survive a restart. A purchase
 *    token is `morphe-<productId>`, which is what hook 3 parses back.
 *
 * 3. `consumeAsync` — strips the `morphe-` prefix off the token and removes
 *    that id from the persisted set before reporting success, so consumables
 *    are granted once instead of on every launch.
 *
 * 4. `acknowledgePurchase` — reports OK without asking Google (Google has never
 *    heard of these purchases, and the JSON ships `acknowledged:true` anyway —
 *    this is belt and braces for a C# side that acknowledges unconditionally).
 *
 * `zzcw.consumeAsync` / `zzcw.acknowledgePurchase` are additionally re-routed
 * straight to super: the stock wrappers run those calls on the client's
 * ExecutorService (zzcw.java:208/225), which would push our fabricated
 * callbacks into the C# AndroidJavaProxy from a background thread.
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
 *  BillingClientImpl.acknowledgePurchase .registers 9  → v0..v5
 *  zzcw.consumeAsync / acknowledgePurchase .registers 5 → p0, p1, p2 only
 */
@Suppress("unused")
val magicSurvivalFreeStorePatch = bytecodePatch(
    name = "Magic Survival Free Store",
    description = "Grants every in-app purchase for free: tapping Buy fabricates a successful " +
        "Google Play purchase (remove-ad, gem packs) and feeds it through the billing listener " +
        "without opening the payment dialog, and granted entitlements are restored on every launch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MAGIC_SURVIVAL)

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

            # ---- persist the grant so queryPurchasesAsync can restore it ----
            iget-object v9, v12, Lcom/android/billingclient/api/BillingClientImpl;->zzg:Landroid/content/Context;
            if-eqz v9, :skip_record
            const-string v10, "morphe_iap"
            const/4 v11, 0x0
            invoke-virtual {v9, v10, v11}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
            move-result-object v9
            const-string v10, "owned"
            const/4 v11, 0x0
            invoke-interface {v9, v10, v11}, Landroid/content/SharedPreferences;->getStringSet(Ljava/lang/String;Ljava/util/Set;)Ljava/util/Set;
            move-result-object v10
            new-instance v11, Ljava/util/HashSet;
            if-eqz v10, :fresh_set
            invoke-direct {v11, v10}, Ljava/util/HashSet;-><init>(Ljava/util/Collection;)V
            goto :set_ready

            :fresh_set
            invoke-direct {v11}, Ljava/util/HashSet;-><init>()V

            :set_ready
            invoke-virtual {v11, v1}, Ljava/util/HashSet;->add(Ljava/lang/Object;)Z
            invoke-interface {v9}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v9
            const-string v10, "owned"
            invoke-interface {v9, v10, v11}, Landroid/content/SharedPreferences${'$'}Editor;->putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v9
            invoke-interface {v9}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V

            :skip_record
            # ---- build the fabricated Purchase (v1 still holds the productId) ----
            new-instance v3, Ljava/lang/StringBuilder;
            invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
            const-string v4, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.vkslrzm.Zombie\",\"productId\":\""
            invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v3, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v4, "\",\"purchaseState\":0,\"purchaseTime\":0,\"purchaseToken\":\"morphe-"
            invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v3, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v4, "\",\"quantity\":1,\"acknowledged\":true}"
            invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4

            new-instance v5, Lcom/android/billingclient/api/Purchase;
            const-string v3, "morphe-sig"
            invoke-direct {v5, v4, v3}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

            new-instance v6, Ljava/util/ArrayList;
            invoke-direct {v6}, Ljava/util/ArrayList;-><init>()V
            invoke-virtual {v6, v5}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

            # ---- OK BillingResult ----
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v7
            const/4 v8, 0x0
            invoke-virtual {v7, v8}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v7
            invoke-virtual {v7}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v7

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
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v2
            const/4 v3, 0x0
            invoke-virtual {v2, v3}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v2
            invoke-virtual {v2}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v2
            return-object v2
            """.trimIndent()
        )

        // ==========================================================
        // 2 — Restore: rebuild queryPurchasesAsync's answer from our set.
        // ==========================================================
        BillingQueryPurchasesAsyncFingerprint.method.addInstructionsWithLabels(
            0, """
            # p0 = BillingClientImpl, p1 = QueryPurchasesParams, p2 = PurchasesResponseListener
            new-instance v2, Ljava/util/ArrayList;
            invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V

            iget-object v0, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzg:Landroid/content/Context;
            if-eqz v0, :deliver
            const-string v1, "morphe_iap"
            const/4 v3, 0x0
            invoke-virtual {v0, v1, v3}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
            move-result-object v0

            const-string v1, "owned"
            const/4 v3, 0x0
            invoke-interface {v0, v1, v3}, Landroid/content/SharedPreferences;->getStringSet(Ljava/lang/String;Ljava/util/Set;)Ljava/util/Set;
            move-result-object v1
            if-eqz v1, :deliver

            invoke-interface {v1}, Ljava/util/Set;->iterator()Ljava/util/Iterator;
            move-result-object v3

            :loop
            invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z
            move-result v4
            if-eqz v4, :deliver

            invoke-interface {v3}, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v4
            check-cast v4, Ljava/lang/String;

            new-instance v5, Ljava/lang/StringBuilder;
            invoke-direct {v5}, Ljava/lang/StringBuilder;-><init>()V
            const-string v6, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.vkslrzm.Zombie\",\"productId\":\""
            invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v5, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v6, "\",\"purchaseState\":0,\"purchaseTime\":0,\"purchaseToken\":\"morphe-"
            invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v5, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v6, "\",\"quantity\":1,\"acknowledged\":true}"
            invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v5}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v5

            const-string v6, "morphe-sig"
            new-instance v0, Lcom/android/billingclient/api/Purchase;
            invoke-direct {v0, v5, v6}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
            invoke-virtual {v2, v0}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            goto :loop

            :deliver
            check-cast v2, Ljava/util/List;
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            invoke-interface {p2, v0, v2}, Lcom/android/billingclient/api/PurchasesResponseListener;->onQueryPurchasesResponse(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 3 — Consume: drop the id from the persisted set, report OK.
        // ==========================================================
        BillingConsumeAsyncFingerprint.method.addInstructionsWithLabels(
            0, """
            # p0 = BillingClientImpl, p1 = ConsumeParams, p2 = ConsumeResponseListener
            invoke-virtual {p1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :build_result

            # token is "morphe-<productId>"; anything shorter is not ours
            invoke-virtual {v0}, Ljava/lang/String;->length()I
            move-result v1
            const/4 v2, 0x7
            if-lt v1, v2, :build_result

            invoke-virtual {v0, v2}, Ljava/lang/String;->substring(I)Ljava/lang/String;
            move-result-object v1

            iget-object v2, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzg:Landroid/content/Context;
            if-eqz v2, :build_result
            const-string v3, "morphe_iap"
            const/4 v4, 0x0
            invoke-virtual {v2, v3, v4}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
            move-result-object v2

            const-string v3, "owned"
            const/4 v4, 0x0
            invoke-interface {v2, v3, v4}, Landroid/content/SharedPreferences;->getStringSet(Ljava/lang/String;Ljava/util/Set;)Ljava/util/Set;
            move-result-object v4
            if-eqz v4, :build_result

            new-instance v3, Ljava/util/HashSet;
            invoke-direct {v3, v4}, Ljava/util/HashSet;-><init>(Ljava/util/Collection;)V
            invoke-virtual {v3, v1}, Ljava/util/HashSet;->remove(Ljava/lang/Object;)Z
            invoke-interface {v2}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v2
            const-string v1, "owned"
            invoke-interface {v2, v1, v3}, Landroid/content/SharedPreferences${'$'}Editor;->putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v2
            invoke-interface {v2}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V

            :build_result
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v2
            const/4 v3, 0x0
            invoke-virtual {v2, v3}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v2
            invoke-virtual {v2}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v2
            invoke-interface {p2, v2, v0}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 4 — Acknowledge: answer OK locally.
        // ==========================================================
        BillingAcknowledgePurchaseFingerprint.method.addInstructions(
            0, """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
            return-void
            """.trimIndent()
        )

        // ==========================================================
        // 5 — Keep the two async wrappers on the caller's thread.
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

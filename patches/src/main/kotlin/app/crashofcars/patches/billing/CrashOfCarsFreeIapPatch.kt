package app.crashofcars.patches.billing

import app.crashofcars.patches.shared.Constants.COMPATIBILITY_CRASH_OF_CARS
import app.crashofcars.patches.shared.Constants.PACKAGE_NAME
import app.crashofcars.patches.shared.Constants.SKUS
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BILLING_CLIENT_IMPL = "Lcom/android/billingclient/api/BillingClientImpl;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"
private const val BILLING_RESULT = "Lcom/android/billingclient/api/BillingResult;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val PURCHASE = "Lcom/android/billingclient/api/Purchase;"
private const val PURCHASES_UPDATED_LISTENER = "Lcom/android/billingclient/api/PurchasesUpdatedListener;"
private const val PURCHASES_LISTENER = "Lcom/android/billingclient/api/PurchasesResponseListener;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val LIST = "Ljava/util/List;"

/**
 * Crash of Cars — Free IAP (Google Play Billing 9.0.0 + Unity IAP / IL2CPP)
 * (com.notdoppler.crashofcars v1.9.34 / versionCode 1090034, Unity 6000.3.22f1)
 *
 * Port of the production-verified Traffic Racer billing patch, with the three
 * Billing **9.0.0** accessor deltas applied. Strategy is the same: spoof the
 * trusted Java source so the Play sheet never opens and the C# layer grants
 * instantly. There is no receipt validator anywhere on either layer
 * (`CrossPlatformValidator` / `GooglePlayValidator` / `IGooglePlayValidator` /
 * `PurchaseVerificationResponse` all score 0 occurrences in the unencrypted
 * `global-metadata.dat`, and the whole `com.unity3d.services.store.*` stack is a
 * pure passthrough whose only gate is `BillingResultResponseCode == OK` — see
 * `analysis/com.notdoppler.crashofcars/notes/targets.md` §1/§2), so a synthetic
 * `Purchase` reaches `OnPurchaseConfirmed` with nothing in between to reject it.
 *
 * Five hooks on `com.android.billingclient.api.BillingClientImpl`:
 *   1. `launchBillingFlow` (:10089) — fake `Purchase` for the tapped SKU,
 *      delivered via `this.zzf → zzab.zzf()` → `onPurchasesUpdated(OK, [p])`.
 *      Body lives in an injected `morpheFakePurchase` helper because the real
 *      method carries an exception table.
 *   2. `acknowledgePurchase` (:8744) — OK callback (non-consumables).
 *   3. `consumeAsync` (:8793) — OK callback + token (consumable packs).
 *   4. `queryProductDetailsAsync` (:11744) — fake catalogue.
 *   5. `queryPurchasesAsync` (:11807) — fake owned list, so entitlements are
 *      re-delivered at every launch.
 *
 * ── The three Billing 9.0.0 deltas vs the Traffic Racer 8.0.0 template ──
 *
 * **(1) Listener delivery path.** The 8.0.0 field is `zzf:L…/zzs;` read via
 * `zzs;->zzd()`. In 9.0.0 it is `zzf:Lcom/android/billingclient/api/zzab;`
 * (`BillingClientImpl.smali:43`) and the `PurchasesUpdatedListener` comes from
 * **`zzab.zzf()`** (`zzab.smali:132`). `zzab.zzd()` (`zzab.smali:124`) returns a
 * `DeveloperProvidedBillingListener` — passing that to `invoke-interface
 * onPurchasesUpdated` throws `ClassCastException`. The helper mirrors the real
 * body's two null checks (`BillingClientImpl.smali:10103-10113`): bail if `zzf`
 * is null, then bail if `zzf.zzf()` is null.
 *
 * **(2) `queryProductDetailsAsync` iteration.** `QueryProductDetailsParams.zza()`
 * (`QueryProductDetailsParams.smali:48`) returns
 * `Lcom/google/android/gms/internal/play_billing/zzca;` instead of the 8.0.0
 * `zzbt`. The patch walks it with `Collection.iterator()` /
 * `Iterator.hasNext()` / `Iterator.next()` rather than `List.get(I)` +
 * `List.size()`. `Collection.iterator()` is guaranteed by the type hierarchy
 * (`zzca` → `zzbx` → `AbstractCollection`), so the loop resolves regardless of
 * which concrete collection the builder actually produced.
 *
 * **(3) `queryPurchasesAsync` register file is 10, not 9.** Locals are `v0..v6`,
 * then `p0=v7`, `p1=v8`, **`p2=v9`**. The fake-owned-purchases body below is
 * budgeted against `v0..v4` with `p2` as the `PurchasesResponseListener`, and
 * the method is `.method public final` so the fingerprint needs
 * `accessFlags = listOf(PUBLIC, FINAL)`.
 *
 * ── Register-overlap discipline (community CHANGELOG) ──
 * The `aput-wide` collision (a register used both as an `invoke` argument index
 * and as the low half of a `move-result-wide` pair) is avoided by giving the
 * `currentTimeMillis()` wide pair its own exclusive registers **v4, v5** in
 * `morpheFakePurchase`; the `StringBuilder` index (v3) and the string constants
 * (v6) never touch them. In `queryPurchasesAsync` there is no wide value at all.
 *
 * ── Label safety (community CHANGELOG) ──
 * The `:sku_old` / `:sku_done` SkuDetails fallback of the 8.0.0 template is
 * **gone** — `Lcom/android/billingclient/api/SkuDetails;` does not exist in
 * Billing 9.0.0 at all (only the unrelated `com.unity3d.services.store.gpbl.*`
 * bridge classes remain), so the branch could never have linked. With it gone,
 * `morpheFakePurchase` has exactly one label pair (`:no_product` / `:sku_done`),
 * each defined *after* its last forward reference and each followed by a real
 * instruction, and it is injected into a **freshly created method with no
 * exception table** — which is precisely the condition under which
 * `addInstructionsWithLabels` is safe. The real `launchBillingFlow` is only ever
 * given a 4-instruction, label-free shim.
 *
 * SKU-agnostic by design: the grant-on-tap path reads the productId straight off
 * the tapped `BillingFlowParams`, so it needs no catalogue at all.
 *
 * NOT patched (by design): `startConnection` / `isReady` — leaving them untouched
 * is what prevents `OnStoreDisconnected` / `OnPurchasesFetchFailed` from firing.
 */
@Suppress("unused")
val crashOfCarsFreeIapPatch = bytecodePatch(
    name = "Crash of Cars Free IAP",
    description = "Every store item — coin packs, gem bundles, remove ads and the starter bundles — is granted the instant you tap Buy, and no Google Play payment sheet ever opens. Because nothing is fetched from Play, purchases work fully offline with no Play account, and remove-ads stays unlocked across restarts since the entitlement is re-delivered from a fake owned-purchases list at every launch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CRASH_OF_CARS)

    execute {
        // ── Fail loudly rather than ship an unpatched APK ──
        BillingClientImplLaunchBillingFlowFingerprint.matchAllOrNull()
            .requireExactlyOne("launchBillingFlow")
        BillingClientImplAcknowledgePurchaseFingerprint.matchAllOrNull()
            .requireExactlyOne("acknowledgePurchase")
        BillingClientImplConsumeAsyncFingerprint.matchAllOrNull()
            .requireExactlyOne("consumeAsync")
        BillingClientImplQueryProductDetailsAsyncFingerprint.matchAllOrNull()
            .requireExactlyOne("queryProductDetailsAsync")
        BillingClientImplQueryPurchasesAsyncFingerprint.matchAllOrNull()
            .requireExactlyOne("queryPurchasesAsync")

        // ═══ 1. launchBillingFlow — INSTANT GRANT ═══
        // Helper method because the real one carries an exception table.
        //
        // `.registers 12` → locals v0..v9, p0=v10 (this), p1=v11 (BillingFlowParams).
        //   v0 = OK BillingResult (held to the end)
        //   v1 = product list from zzk(), then scratch
        //   v2 = size / index / ProductDetailsParams / ProductDetails / productId
        //   v3 = StringBuilder
        //   v4,v5 = currentTimeMillis() wide pair — EXCLUSIVE, never an arg register
        //   v6 = json String, then the zzab holder, then the listener
        //   v7 = empty signature ""
        //   v8 = Purchase
        //   v9 = ArrayList<Purchase>
        val billingClientImplClass = BillingClientImplLaunchBillingFlowFingerprint.classDef
        val morpheFakePurchase = ImmutableMethod(
            BILLING_CLIENT_IMPL,
            "morpheFakePurchase",
            listOf(
                ImmutableMethodParameter(
                    "Lcom/android/billingclient/api/BillingFlowParams;",
                    null,
                    null
                )
            ),
            BILLING_RESULT,
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(12)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # ── 1. BillingResult OK ──
                invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v0
                const-string v1, "OK"
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
                move-result-object v0
                invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT
                move-result-object v0

                # ── 2. Product id from p1 (BillingFlowParams) ──
                #    Billing 9.0.0: the SkuDetails API is gone, so zzk() is the only
                #    product source. zze is always set by the constructor, never null.
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()$LIST
                move-result-object v1
                invoke-interface {v1}, $LIST->size()I
                move-result v2
                if-lez v2, :no_product
                const/4 v2, 0x0
                invoke-interface {v1, v2}, $LIST->get(I)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, $BFP_PRODUCT_DETAILS_PARAMS
                invoke-virtual {v2}, $BFP_PRODUCT_DETAILS_PARAMS->zza()Lcom/android/billingclient/api/ProductDetails;
                move-result-object v2
                invoke-virtual {v2}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
                move-result-object v2
                goto :sku_done

                :no_product
                const-string v2, ""

                :sku_done

                # ── 3. Purchase JSON (v4/v5 wide pair is exclusive) ──
                new-instance v3, Ljava/lang/StringBuilder;
                invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
                invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                move-result-wide v4
                const-string v6, "{\"productId\":\""
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                const-string v6, "\",\"purchaseToken\":\"morphe-"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v6, "\",\"orderId\":\"GPA."
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v6, "\",\"purchaseState\":0,\"packageName\":\"$PACKAGE_NAME\",\"purchaseTime\":"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v6, ",\"acknowledged\":false}"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v6

                const-string v7, ""
                new-instance v8, $PURCHASE
                invoke-direct {v8, v6, v7}, $PURCHASE-><init>(Ljava/lang/String;Ljava/lang/String;)V

                # ── 4. Deliver via the PurchasesUpdatedListener ──
                #    Billing 9.0.0: field zzf is a zzab and the listener is zzf(),
                #    NOT zzd() (that returns a DeveloperProvidedBillingListener).
                #    Both null checks mirror the real launchBillingFlow body.
                iget-object v6, p0, $BILLING_CLIENT_IMPL->zzf:Lcom/android/billingclient/api/zzab;
                if-eqz v6, :deliver_done
                invoke-virtual {v6}, Lcom/android/billingclient/api/zzab;->zzf()$PURCHASES_UPDATED_LISTENER
                move-result-object v6
                if-eqz v6, :deliver_done
                new-instance v9, $ARRAY_LIST
                invoke-direct {v9}, $ARRAY_LIST-><init>()V
                invoke-virtual {v9, v8}, $ARRAY_LIST->add(Ljava/lang/Object;)Z
                invoke-interface {v6, v0, v9}, $PURCHASES_UPDATED_LISTENER->onPurchasesUpdated($BILLING_RESULT$LIST)V

                :deliver_done
                return-object v0
                nop
            """.trimIndent())
        }
        billingClientImplClass.methods.add(morpheFakePurchase)

        // 4-instruction, label-free shim in the real method (exception table intact).
        BillingClientImplLaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, $BILLING_CLIENT_IMPL->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)$BILLING_RESULT
            move-result-object v0
            return-object v0
        """.trimIndent())

        // ═══ 2. acknowledgePurchase — non-consumables finish here ═══
        // .registers 9 → locals v0..v5, p0=v6, p1=v7, p2=v8 (listener)
        BillingClientImplAcknowledgePurchaseFingerprint.method.addInstructions(0, """
            invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT
            move-result-object v0
            invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse($BILLING_RESULT)V
            return-void
        """.trimIndent())

        // ═══ 3. consumeAsync — consumable packs finish here ═══
        // .registers 9 → locals v0..v5, p0=v6, p1=v7, p2=v8 (listener)
        BillingClientImplConsumeAsyncFingerprint.method.addInstructions(0, """
            invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT
            move-result-object v0
            invoke-virtual {p1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
            move-result-object v1
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse($BILLING_RESULT Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ═══ 4. queryProductDetailsAsync — fake catalogue ═══
        // The method has one label (:cond_34) but NO exception table, so
        // addInstructionsWithLabels is safe here.
        //
        // ⚠️ Billing 9.0.0 nests the price under `oneTimePurchaseOfferDetails`.
        // `ProductDetails.smali` only parses the keys: productId, type, title,
        // name, description, iconUrl, packageName, packageDisplayName,
        // serializedDocid, oneTimePurchaseOfferDetails(List). A FLAT
        // price/priceCurrencyCode/priceAmountMicros JSON parses "successfully"
        // but leaves the offer details null, and that surfaces as an NRE inside
        // the GAME, not here — observed on-device:
        //     NullReferenceException
        //       at ShopUtils.GetLocalisedPrice (IBillingProduct)
        //       at UIGemsPack.Refresh / UIGems.RebuildPacks
        //     NullReferenceException
        //       at StoreController.AddIAPAnalytics (...)
        //       at StoreController.<>c__DisplayClass14_0.<PurchaseGemPack>b__0(Boolean)
        // The second one is thrown *inside the purchase-success callback*, so the
        // code that dismisses the spinner never runs and the UI hangs on the
        // loading wheel forever. Emitting a complete, correctly-nested
        // ProductDetails is therefore load-bearing, not cosmetic.
        //
        // `.registers 9` → locals v0..v5, p0=v6, p1=v7, p2=v8 (listener).
        //   v0 = OK BillingResult (built first, held to the end)
        //   v1 = Iterator over zza(), then the empty List for create()
        //   v2 = ArrayList<ProductDetails>
        //   v3 = hasNext() flag, then the current Product
        //   v4 = StringBuilder, then the json String
        //   v5 = string constant, then the ProductDetails instance
        //
        // At the :cond_loop back-edge merge v3/v4/v5 are still undefined on the
        // entry path, so the int-vs-reference reuse of v3 merges cleanly (no
        // verifier conflict) and nothing is read there.
        BillingClientImplQueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(0, """
            invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT
            move-result-object v0

            new-instance v2, $ARRAY_LIST
            invoke-direct {v2}, $ARRAY_LIST-><init>()V

            # Billing 9.0.0: zza() returns play_billing/zzca, not the 8.0.0 zbt.
            # Walk it as a Collection — Collection.iterator() is guaranteed by the
            # type hierarchy, so this resolves no matter which concrete class the
            # QueryProductDetailsParams.Builder actually produced.
            invoke-virtual {p1}, Lcom/android/billingclient/api/QueryProductDetailsParams;->zza()Lcom/google/android/gms/internal/play_billing/zzca;
            move-result-object v1
            invoke-interface {v1}, Ljava/util/Collection;->iterator()Ljava/util/Iterator;
            move-result-object v1

            :cond_loop
            invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
            move-result v3
            if-eqz v3, :done

            invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v3
            check-cast v3, $QUERY_PRODUCT

            new-instance v4, Ljava/lang/StringBuilder;
            invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V
            const-string v5, "{\"productId\":\""
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v3}, $QUERY_PRODUCT->zza()Ljava/lang/String;
            move-result-object v5
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"type\":\""
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v3}, $QUERY_PRODUCT->zzb()Ljava/lang/String;
            move-result-object v5
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"title\":\"Morphe\",\"name\":\"Morphe\",\"description\":\"Morphe\",\"oneTimePurchaseOfferDetails\":{\"formattedPrice\":\"${'$'}1.99\",\"priceAmountMicros\":1990000,\"priceCurrencyCode\":\"USD\",\"offerId\":\"\"}}"
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4
            new-instance v5, Lcom/android/billingclient/api/ProductDetails;
            invoke-direct {v5, v4}, Lcom/android/billingclient/api/ProductDetails;-><init>(Ljava/lang/String;)V
            invoke-virtual {v2, v5}, $ARRAY_LIST->add(Ljava/lang/Object;)Z
            goto :cond_loop

            :done
            new-instance v1, $ARRAY_LIST
            invoke-direct {v1}, $ARRAY_LIST-><init>()V
            invoke-static {v2, v1}, Lcom/android/billingclient/api/QueryProductDetailsResult;->create($LIST$LIST)Lcom/android/billingclient/api/QueryProductDetailsResult;
            move-result-object v1
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ProductDetailsResponseListener;->onProductDetailsResponse($BILLING_RESULT Lcom/android/billingclient/api/QueryProductDetailsResult;)V
            return-void
            nop
        """.trimIndent())

        // ═══ 5. queryPurchasesAsync — fake owned list (persistence / auto-grant) ═══
        // Label-free straight-line body, injected directly into the real method.
        //
        // `.registers 10` (Billing 9.0.0 delta — the 8.0.0 template assumed 9)
        //   → locals v0..v6, p0=v7, p1=v8, p2=v9 (PurchasesResponseListener)
        BillingClientImplQueryPurchasesAsyncFingerprint.method.addInstructions(0, fakeOwnedPurchasesSmali())
    }
}

/**
 * Fabricates an OK `BillingResult` plus an `ArrayList<Purchase>` holding one
 * `new Purchase(json, "")` per SKU in [Constants.SKUS], hands them to
 * `purchasesResponseListener.onQueryPurchasesResponse(ok, list)` and returns —
 * the real Play query never runs, so this works offline.
 *
 * The JSONs are static (deterministic token/order, fixed purchaseTime) so the
 * entitlement is stable across restarts, and they embed
 * `packageName = com.notdoppler.crashofcars`.
 *
 * Register budget against the target's `.registers 10` — v0 result, v1 list,
 * v2 json, v3 scratch, v4 empty signature; v5/v6 unused; `p2` (v9) is the
 * listener. No wide values, so there is no index/wide overlap hazard.
 */
private fun fakeOwnedPurchasesSmali(): String {
    val json = { sku: String ->
        // Escape the double-quotes for the smali const-string.
        (
            "{\"productId\":\"$sku\"," +
                "\"purchaseToken\":\"morphe-$sku\"," +
                "\"orderId\":\"GPA.00000000-0000-0000-0000-000000000000\"," +
                "\"purchaseState\":0," +
                "\"packageName\":\"$PACKAGE_NAME\"," +
                "\"purchaseTime\":1727305600000," +
                "\"acknowledged\":false}"
            ).replace("\"", "\\\"")
    }

    val sb = StringBuilder()

    // ── Build OK BillingResult: BillingResult.newBuilder().setResponseCode(0)
    //    .setDebugMessage("OK").build() ──
    sb.appendLine("invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER")
    sb.appendLine("move-result-object v0")
    sb.appendLine("const/4 v3, 0x0")
    sb.appendLine("invoke-virtual {v0, v3}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER")
    sb.appendLine("move-result-object v0")
    sb.appendLine("const-string v3, \"OK\"")
    sb.appendLine("invoke-virtual {v0, v3}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER")
    sb.appendLine("move-result-object v0")
    sb.appendLine("invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT")
    sb.appendLine("move-result-object v0")

    // ── Build ArrayList<Purchase> with one fake purchase per owned SKU ──
    sb.appendLine("new-instance v1, $ARRAY_LIST")
    sb.appendLine("invoke-direct {v1}, $ARRAY_LIST-><init>()V")
    sb.appendLine("const-string v4, \"\"")
    for (sku in SKUS) {
        sb.appendLine("const-string v2, \"${json(sku)}\"")
        sb.appendLine("new-instance v3, $PURCHASE")
        sb.appendLine("invoke-direct {v3, v2, v4}, $PURCHASE-><init>(Ljava/lang/String;Ljava/lang/String;)V")
        sb.appendLine("invoke-virtual {v1, v3}, $ARRAY_LIST->add(Ljava/lang/Object;)Z")
    }

    // ── Deliver the faked response, skip the real query ──
    sb.appendLine("invoke-interface {p2, v0, v1}, $PURCHASES_LISTENER->onQueryPurchasesResponse($BILLING_RESULT$LIST)V")
    sb.appendLine("return-void")

    return sb.toString().trimIndent()
}

/**
 * Throws [PatchException] unless [this] contains exactly one match — i.e. the
 * target method moved, was renamed, or the billing library changed shape.
 * Silently skipping a hook would ship an APK where only *some* of the purchase
 * flow is faked, which is worse than not patching at all.
 */
private fun List<Match>?.requireExactlyOne(target: String) {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "Crash of Cars Free IAP: BillingClientImpl.$target matched $count time(s), expected exactly 1. " +
                "The app's Play Billing library layout has changed — refusing to emit a partially patched APK."
        )
    }
}
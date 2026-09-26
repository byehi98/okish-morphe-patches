package app.trafficracer.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.trafficracer.patches.shared.Constants.COMPATIBILITY_TRAFFICRACER
import app.trafficracer.patches.shared.Constants.PACKAGE_NAME_TRAFFICRACER
import app.trafficracer.patches.shared.Constants.SKUS
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val BILLING_RESULT = "Lcom/android/billingclient/api/BillingResult;"
private const val PURCHASE = "Lcom/android/billingclient/api/Purchase;"
private const val PURCHASES_LISTENER = "Lcom/android/billingclient/api/PurchasesResponseListener;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val LIST = "Ljava/util/List;"

/**
 * Traffic Racer — IAP Bypass (Google Play Billing 8.0.0 + Unity IAP 5.0.3)
 * (com.skgames.trafficracer v4.0 / versionCode 404, Unity 6000.2.15f1 IL2CPP)
 *
 * Port of the production-verified Traffic Rider billing patch — every
 * fingerprint matches byte-identical in Traffic Racer's smali (same billing
 * lib `billing@@8.0.0`, same engine build). Strategy: spoof the trusted Java
 * source. No Play sheet ever opens; C# grants instantly.
 *
 * Five hooks on com.android.billingclient.api.BillingClientImpl:
 *   1. launchBillingFlow → fake Purchase for the tapped SKU, delivered via
 *      this.zzf → zzs.zzd() → onPurchasesUpdated(OK, [purchase]), return OK.
 *      Body lives in an injected `morpheFakePurchase` helper because the real
 *      method carries an exception table (labeled blocks would be dropped).
 *   2. acknowledgePurchase → OK listener callback (non-consumables finish here).
 *   3. consumeAsync → OK listener callback + token (consumables finish here).
 *   4. queryProductDetailsAsync → fake ProductDetails per queried SKU.
 *   5. queryPurchasesAsync → fake owned-purchases list (public final — access
 *      flags differ from Traffic Rider) so entitlements survive restart and are
 *      granted at startup. Fake list = Constants.SKUS (non-consumables only).
 *
 * Unlike Traffic Rider, this binary has NO receipt validator at all
 * (CrossPlatformValidator / GooglePlayValidator absent — see iap-bypass.md §3),
 * so the synthetic Purchase reaches OnPurchaseConfirmed(Order) → grant with
 * nothing in between to reject it.
 *
 * Package-specific change vs the Traffic Rider source: the fabricated Purchase
 * JSON embeds "packageName":"com.skgames.trafficracer" (via Constants).
 *
 * SKU-agnostic: no hardcoded catalog needed for taps — the SKU is read from the
 * tapped BillingFlowParams (zzk()/zzj()) and catalog queries are echoed back.
 *
 * NOT patched (by design): startConnection / isReady — leaving them untouched
 * keeps OnStoreDisconnected / OnPurchasesFetchFailed from firing.
 */
@Suppress("unused")
val trafficRacerBillingBypassPatch = bytecodePatch(
    name = "Traffic Racer IAP Bypass",
    description = "Everything in the store is free with one tap — cash packs, double cash, remove ads, starter kit and the premium car. Just tap Buy and it grants instantly, no Google Play payment needed, and purchases persist across restarts.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRAFFICRACER)

    execute {
        // ═══ 1. launchBillingFlow — INSTANT GRANT (helper due to exception-table label-drop) ═══
        val billingClientImplClass = BillingClientImplLaunchBillingFlowFingerprint.classDef
        val morpheFakePurchase = ImmutableMethod(
            "Lcom/android/billingclient/api/BillingClientImpl;",
            "morpheFakePurchase",
            listOf(
                ImmutableMethodParameter(
                    "Lcom/android/billingclient/api/BillingFlowParams;",
                    null,
                    null
                )
            ),
            "Lcom/android/billingclient/api/BillingResult;",
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(9)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # ── 1. BillingResult OK ──
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v0
                const-string v1, "OK"
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
                move-result-object v0
                invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v0

                # ── 2. SKU from p1 (BillingFlowParams): new-style zzk() ──
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
                move-result-object v1
                invoke-interface {v1}, Ljava/util/List;->isEmpty()Z
                move-result v2
                if-nez v2, :sku_old
                const/4 v2, 0x0
                invoke-interface {v1, v2}, Ljava/util/List;->get(I)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, $BFP_PRODUCT_DETAILS_PARAMS
                invoke-virtual {v2}, $BFP_PRODUCT_DETAILS_PARAMS->zza()Lcom/android/billingclient/api/ProductDetails;
                move-result-object v2
                invoke-virtual {v2}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
                move-result-object v2
                goto :sku_done

                :sku_old
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v1, v2}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, Lcom/android/billingclient/api/SkuDetails;
                invoke-virtual {v2}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                move-result-object v2

                :sku_done
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
                const-string v6, "-1234-5678-9012-34567\",\"purchaseState\":0,\"packageName\":\"$PACKAGE_NAME_TRAFFICRACER\",\"purchaseTime\":"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v6, ",\"acknowledged\":false}"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v4

                const-string v5, ""
                new-instance v6, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v6, v4, v5}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

                iget-object v4, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzf:Lcom/android/billingclient/api/zzs;
                invoke-virtual {v4}, Lcom/android/billingclient/api/zzs;->zzd()Lcom/android/billingclient/api/PurchasesUpdatedListener;
                move-result-object v4

                new-instance v5, Ljava/util/ArrayList;
                invoke-direct {v5}, Ljava/util/ArrayList;-><init>()V
                invoke-virtual {v5, v6}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

                invoke-interface {v4, v0, v5}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                return-object v0
                nop
            """.trimIndent())
        }
        billingClientImplClass.methods.add(morpheFakePurchase)

        BillingClientImplLaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, Lcom/android/billingclient/api/BillingClientImpl;->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent())

        // ═══ 2. acknowledgePurchase ═══
        BillingClientImplAcknowledgePurchaseFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
            return-void
        """.trimIndent())

        // ═══ 3. consumeAsync ═══
        BillingClientImplConsumeAsyncFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            invoke-virtual {p1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
            move-result-object v1
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ═══ 4. queryProductDetailsAsync — fake catalog ═══
        BillingClientImplQueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(0, """
            invoke-virtual {p1}, Lcom/android/billingclient/api/QueryProductDetailsParams;->zza()Lcom/google/android/gms/internal/play_billing/zzbt;
            move-result-object v1
            new-instance v2, Ljava/util/ArrayList;
            invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
            const/4 v3, 0x0
            :cond_loop
            invoke-interface {v1}, Ljava/util/List;->size()I
            move-result v4
            if-ge v3, v4, :done
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v4, "{\"productId\":\""
            invoke-virtual {v0, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-interface {v1, v3}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v4
            check-cast v4, $QUERY_PRODUCT
            invoke-virtual {v4}, $QUERY_PRODUCT->zza()Ljava/lang/String;
            move-result-object v4
            invoke-virtual {v0, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"type\":\""
            invoke-virtual {v0, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-interface {v1, v3}, Ljava/util/List;->get(I)Ljava/lang/Object;
            move-result-object v4
            check-cast v4, $QUERY_PRODUCT
            invoke-virtual {v4}, $QUERY_PRODUCT->zzb()Ljava/lang/String;
            move-result-object v4
            invoke-virtual {v0, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"title\":\"Morphe\",\"name\":\"Morphe\",\"price\":\"${'$'}1.99\",\"priceCurrencyCode\":\"USD\",\"originalPrice\":\"${'$'}1.99\",\"originalPriceAmountMicros\":1990000,\"priceAmountMicros\":1990000}"
            invoke-virtual {v0, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4
            new-instance v5, Lcom/android/billingclient/api/ProductDetails;
            invoke-direct {v5, v4}, Lcom/android/billingclient/api/ProductDetails;-><init>(Ljava/lang/String;)V
            invoke-interface {v2, v5}, Ljava/util/List;->add(Ljava/lang/Object;)Z
            add-int/lit8 v3, v3, 0x1
            goto :cond_loop
            :done
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            new-instance v1, Ljava/util/ArrayList;
            invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
            invoke-static {v2, v1}, Lcom/android/billingclient/api/QueryProductDetailsResult;->create(Ljava/util/List;Ljava/util/List;)Lcom/android/billingclient/api/QueryProductDetailsResult;
            move-result-object v1
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ProductDetailsResponseListener;->onProductDetailsResponse(Lcom/android/billingclient/api/BillingResult;Lcom/android/billingclient/api/QueryProductDetailsResult;)V
            return-void
            nop
        """.trimIndent())

        // ═══ 5. queryPurchasesAsync — fake owned list (persistence / auto-grant) ═══
        // Label-free straight-line body, injected directly into the real method
        // (public final, .registers 9 → locals v0..v5, p0=v6, p1=v7, p2=v8).
        BillingClientImplQueryPurchasesAsyncFingerprint.method.addInstructions(0, fakeOwnedPurchasesSmali())
    }
}

/**
 * Smali that, at the top of `queryPurchasesAsync`, fabricates an OK
 * BillingResult and an ArrayList<Purchase> with one `new Purchase(json, "")`
 * per SKU in Constants.SKUS, then calls
 * `purchasesResponseListener.onQueryPurchasesResponse(ok, list)` and
 * `return-void` — the real Google Play query never runs.
 *
 * Purchase JSONs are static (deterministic tokens/orders — stable across
 * restarts) and embed packageName = com.skgames.trafficracer.
 *
 * Register usage (target declares .registers 9):
 *   v0 = OK BillingResult (builder scratch first)
 *   v1 = ArrayList<Purchase>
 *   v2 = current JSON string
 *   v3 = scratch (response code / current Purchase)
 *   v4 = empty signature ("")
 *   p2 = PurchasesResponseListener
 */
private fun fakeOwnedPurchasesSmali(): String {
    val json = { sku: String ->
        // Must escape the double-quotes for smali const-string.
        (
            "{\"productId\":\"$sku\"," +
                "\"purchaseToken\":\"morphe-$sku\"," +
                "\"orderId\":\"GPA.00000000-0000-0000-0000-000000000000\"," +
                "\"purchaseState\":0," +
                "\"packageName\":\"$PACKAGE_NAME_TRAFFICRACER\"," +
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

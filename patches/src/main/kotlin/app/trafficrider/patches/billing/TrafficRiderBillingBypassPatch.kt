package app.trafficrider.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.trafficrider.patches.shared.Constants.COMPATIBILITY_TRAFFICRIDER
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"

/**
 * Traffic Rider — IAP Bypass (Google Play Billing 8.0.0 + Unity IAP 5.0.4)
 * (com.skgames.trafficrider v2.11, Unity 6000.2.15f1 IL2CPP)
 *
 * Same engine as pl.macaque.Missiles v1.41: Unity IAP C# → JNI → Java BillingClient.
 * Strategy: spoof the trusted Java source. No Play sheet ever opens; C# grants instantly.
 *
 * Four hooks on com.android.billingclient.api.BillingClientImpl (billing@@8.0.0):
 *   1. launchBillingFlow → fake Purchase for tapped SKU, deliver via this.zzf→zzs.zzd()→onPurchasesUpdated(OK, [purchase]), return OK.
 *   2. acknowledgePurchase → listener OK.
 *   3. consumeAsync → listener OK + token.
 *   4. queryProductDetailsAsync → fake ProductDetails per SKU from params.
 *
 * SKU-agnostic, no hardcoded catalog. Adapted from Missiles patch which is
 * production-verified on same billing version (see missiles/patches/billing docs).
 *
 * IL2CPP note: Traffic Rider stores garage/currency natively and validates via
 * Unity.Purchasing.Security — fake purchases may still be rejected by that
 * validator or require server. This patch is a "try and see" bypass per user
 * request; failures will manifest as purchase not persisting after restart or
 * garage still locked despite OK callback.
 */
@Suppress("unused")
val trafficRiderBillingBypassPatch = bytecodePatch(
    name = "Traffic Rider IAP Bypass",
    description = "Everything in the store is free with one tap — in-game currency, double cash, remove ads, extra continues, all bikes and packs. Just tap Buy and it grants instantly, no Google Play payment needed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_TRAFFICRIDER)

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
                const-string v6, "-1234-5678-9012-34567\",\"purchaseState\":0,\"packageName\":\"com.skgames.trafficrider\",\"purchaseTime\":"
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
    }
}

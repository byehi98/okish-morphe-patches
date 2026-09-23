package app.crossyroad.patches.billing

import app.crossyroad.patches.shared.Constants.COMPATIBILITY_CROSSY_ROAD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors. The \$ escapes keep Kotlin string interpolation from
// treating "$Builder" / "$ProductDetailsParams" / "$Product" as template
// expressions. NOTE: the trailing ';' is part of each descriptor — dropping it
// leaves an unterminated type that makes InlineSmaliCompiler report cascade
// lexer errors several lines downstream.
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"

/**
 * Crossy Road — Free store (Google Play Billing 8.0.0 purchase forge).
 *
 * Strategy: spoof the trusted source. All store taps (characters, coin packs,
 * bundles) funnel C#'s UniversalStoreManager.PurchaseRequest →
 * GenericBiller.PurchaseProduct → GoogleLaunchBillingFlow → the *Java*
 * BillingClientImpl.launchBillingFlow. Faking the Java-side callbacks is
 * indistinguishable from a real Play purchase from IL2CPP's point of view —
 * C# then runs its real grant funnel (OnPurchaseSuccess →
 * HandleProductPurchase → PurchaseProductBundle) with game-owned save code,
 * local receipt status and analytics intact. The game's verifyReceiptV2 call
 * is fire-and-forget (FireAndForgetReceiptValidation) and never gates the
 * local grant. No Play sheet ever opens; nothing is charged.
 *
 * Four hooks on com.android.billingclient.api.BillingClientImpl (public API
 * names — never obfuscated; smali-verified, see Fingerprints.kt):
 *   1. launchBillingFlow           → fake Purchase for the tapped SKU, delivered
 *                                    via this.zzf → zzs.zzd() → onPurchasesUpdated(OK, …),
 *                                    return OK. PRIMARY — this IS the purchase grant.
 *   2. acknowledgePurchase         → p2.onAcknowledgePurchaseResponse(OK) + return
 *                                    (real service would reject the fake token).
 *   3. consumeAsync                → p2.onConsumeResponse(OK, token) + return.
 *   4. queryProductDetailsAsync    → fake ProductDetails catalog per SKU so the
 *                                    store populates / buy buttons enable.
 *
 * WHY the helper (proven BurritoBison/Missiles regression root cause):
 * launchBillingFlow has a LARGE try/catch exception table (.catch entries from
 * smali 8556 through the end). Morphe's instruction-tree writer DROPS every
 * labeled block when merging labeled injections into a method with catches —
 * the body lands truncated and falls through to the original flow. A freshly
 * injected method has NO exception table, so the labeled SKU-read +
 * fake-Purchase body merges intact; launchBillingFlow's injected entry is a
 * tiny straight-line delegate (no labels/branches) — catch-safe.
 *
 * Register budgets (all verified against Crossy smali):
 *  - launchBillingFlow:  .registers 33 → p0=v30, p1=v31, p2=v32. Delegate marshals
 *                        p0/p2 into v0/v1 via /from16 ({v30,v32} is unencodable:
 *                        35c caps at v15, 3rc needs a contiguous range).
 *  - morpheFakePurchase: INJECTED .registers 9 → p0=this=v7, p1=BillingFlowParams=v8,
 *                        locals v0-v6 — every invoke ≤ v15.
 *  - acknowledgePurchase: .registers 9 → p2=v8;  body uses v0-v1 + p2.
 *  - consumeAsync:        .registers 9 → p1=v7, p2=v8; body uses v0-v1 + p1/p2.
 *  - queryProductDetailsAsync: .registers 9 → p0=v6, p1=v7, p2=v8; body uses
 *                        v0-v5 exactly; NO catch table (method 10530-10591), so
 *                        direct labeled injection is safe (Missiles pattern).
 *
 * zz* names (zzf/zzs.zzd/zzj/zzk/zza) are Google's own R8-minified names in
 * the published billing-8.0.0 AAR — stable for this version, re-verify if the
 * billing lib version changes. Purchase ctor arg 2 is the signature — "" is
 * correct (Unity IAP does not verify signatures client-side on GooglePlay).
 *
 * Fake identity is unique per tap (System.currentTimeMillis once → orderId +
 * purchaseToken + purchaseTime), so Unity IAP's transaction dedup never
 * swallows the 2nd+ purchase.
 */
@Suppress("unused")
val crossyRoadFreeStorePatch = bytecodePatch(
    name = "Free store",
    description = "The in-game store is free. Just tap \"Buy\" and the item is yours — no payment needed. For an ad-free game, just buy the ad-block item from the store.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CROSSY_ROAD)

    execute {
        // ═══ 1. launchBillingFlow — INSTANT PURCHASE GRANT (primary) ══════════
        // The forged-purchase body lives in an injected helper (no exception
        // table → labels merge intact); the original launchBillingFlow body
        // stays below the delegate's return as dead code.
        val billingClientImplClass = LaunchBillingFlowFingerprint.classDef
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
                # ── 1. BillingResult OK (persisted in v0, live throughout) ──
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v1, v2}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v1
                invoke-virtual {v1}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v0

                # ── 2. SKU from p1 (BillingFlowParams): zzj() → SkuDetails first
                #      (this billing version's own body reads zzj before zzk),
                #      null-safe via the SDK's own zzby.zza(Iterable, null) ──
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v1
                invoke-static {v1, v2}, Lcom/google/android/gms/internal/play_billing/zzby;->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lcom/android/billingclient/api/SkuDetails;
                if-nez v1, :play_sku

                # ── 2b. fallback: zzk() → ProductDetailsParams → ProductDetails.getProductId() ──
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
                move-result-object v1
                invoke-static {v1, v2}, Lcom/google/android/gms/internal/play_billing/zzby;->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, $BFP_PRODUCT_DETAILS_PARAMS
                if-nez v1, :play_pdp
                const-string v4, "unknown_sku"
                goto :play_json

                :play_pdp
                invoke-virtual {v1}, $BFP_PRODUCT_DETAILS_PARAMS->zza()Lcom/android/billingclient/api/ProductDetails;
                move-result-object v1
                invoke-virtual {v1}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
                move-result-object v4
                goto :play_json

                :play_sku
                invoke-virtual {v1}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                move-result-object v4

                # ── 3. Fake Purchase JSON — unique per tap (one currentTimeMillis,
                #      captured in v2(wide low)/v3, appended at orderId,
                #      purchaseTime and purchaseToken) so Unity IAP's transaction
                #      dedup never swallows repeat purchases ──
                :play_json
                invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                move-result-wide v2
                invoke-static {v2, v3}, Ljava/lang/String;->valueOf(J)Ljava/lang/String;
                move-result-object v2
                new-instance v1, Ljava/lang/StringBuilder;
                invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
                const-string v3, "{\"orderId\":\"fake-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\",\"packageName\":\"com.yodo1.crossyroad\",\"productId\":\""
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\",\"purchaseTime\":"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, ",\"purchaseState\":0,\"purchaseToken\":\"fake-token-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\",\"quantity\":1,\"acknowledged\":false,\"autoRenewing\":false}"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v5

                # ── 4. new Purchase(json, "") — arg2 is the signature ("" = no client verify) ──
                const-string v2, ""
                new-instance v3, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v3, v5, v2}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

                # ── 5. Registered listener: this.zzf → zzs.zzd(), null-guarded
                #      so a pre-connect call returns OK instead of NPE-ing ──
                iget-object v1, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzf:Lcom/android/billingclient/api/zzs;
                if-eqz v1, :play_done
                invoke-virtual {v1}, Lcom/android/billingclient/api/zzs;->zzd()Lcom/android/billingclient/api/PurchasesUpdatedListener;
                move-result-object v1
                if-eqz v1, :play_done
                invoke-static {v3}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v2
                invoke-interface {v1, v0, v2}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V

                :play_done
                return-object v0
                nop
            """.trimIndent())
        }
        billingClientImplClass.methods.add(morpheFakePurchase)

        // launchBillingFlow entry: straight-line delegate to the helper.
        // {p0, p2} = {v30, v32} is unencodable as one invoke — marshal through
        // v0/v1 with explicit /from16 (the inline parser does NOT auto-widen
        // plain move-object). v0/v1 are dead locals: the delegate returns
        // before the original (catch-tabled) body runs.
        LaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, Lcom/android/billingclient/api/BillingClientImpl;->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent())

        // ═══ 2. acknowledgePurchase — complete durables instantly ═════════════
        // Straight-line body (no labels) — safe to inject directly at entry.
        AcknowledgePurchaseFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
            return-void
        """.trimIndent())

        // ═══ 3. consumeAsync — consumables (coin packs) complete instantly ════
        ConsumeAsyncFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            invoke-virtual {p1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
            move-result-object v1
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ═══ 4. queryProductDetailsAsync — fake catalog, SKU-agnostic ═════════
        // Loop p1.zza() → zzbt (implements List) of QueryProductDetailsParams$Product;
        // per product read zzb()=type, zza()=productId, build a fake
        // ProductDetails JSON (ctor requires non-empty "productId"/"type";
        // PRICES are read ONLY from the nested "oneTimePurchaseOfferDetailsList"
        // (preferred; wins when present) or "oneTimePurchaseOfferDetails" object —
        // flat SkuDetails-style top-level price keys are ignored, which left
        // getOneTimePurchaseOfferDetails() null → Unity never received IAP data →
        // buy buttons stuck in PendingIAPData → store showed its generic
        // connection-error text), collect,
        // then deliver p2.onProductDetailsResponse(OK, QueryProductDetailsResult.create(list, empty)).
        // Uses exactly the 6 locals (v0-v5) of .registers 9; method has no catch
        // table, so direct labeled injection is safe.
        QueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(0, """
            # p0=this(v6), p1=QueryProductDetailsParams(v7), p2=ProductDetailsResponseListener(v8)
            # locals: v0 StringBuilder/scratch, v1 product list, v2 ArrayList accumulator,
            #         v3 loop index, v4/v5 temps (const-strings / element / id / type / json)
            invoke-virtual {p1}, Lcom/android/billingclient/api/QueryProductDetailsParams;->zza()Lcom/google/android/gms/internal/play_billing/zzbt;
            move-result-object v1
            new-instance v2, Ljava/util/ArrayList;
            invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
            const/4 v3, 0x0
            :cond_loop
            invoke-interface {v1}, Ljava/util/List;->size()I
            move-result v4
            if-ge v3, v4, :done
            # ── build fake ProductDetails JSON for product i ──
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
            # Price fields MUST be nested under oneTimePurchaseOfferDetails(List):
            # ProductDetails ctor ignores top-level price keys and only builds
            # OneTimePurchaseOfferDetails from "oneTimePurchaseOfferDetailsList"
            # (array, preferred — ctor returns early when non-null) or the
            # "oneTimePurchaseOfferDetails" object fallback; both are emitted
            # (identical) so Java getters and any JSON-string consumer see price.
            # Nested keys (all opt* — no JSONException): formattedPrice,
            # priceAmountMicros (number), priceCurrencyCode.
            const-string v5, "\",\"title\":\"Morphe\",\"name\":\"Morphe\",\"price\":\"${'$'}1.99\",\"priceCurrencyCode\":\"USD\",\"originalPrice\":\"${'$'}1.99\",\"originalPriceAmountMicros\":1990000,\"priceAmountMicros\":1990000,\"oneTimePurchaseOfferDetails\":{\"formattedPrice\":\"${'$'}1.99\",\"priceAmountMicros\":1990000,\"priceCurrencyCode\":\"USD\"},\"oneTimePurchaseOfferDetailsList\":[{\"formattedPrice\":\"${'$'}1.99\",\"priceAmountMicros\":1990000,\"priceCurrencyCode\":\"USD\"}]}"
            invoke-virtual {v0, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4
            # new ProductDetails(json) — package-private ctor, same package → legal
            new-instance v5, Lcom/android/billingclient/api/ProductDetails;
            invoke-direct {v5, v4}, Lcom/android/billingclient/api/ProductDetails;-><init>(Ljava/lang/String;)V
            invoke-interface {v2, v5}, Ljava/util/List;->add(Ljava/lang/Object;)Z
            add-int/lit8 v3, v3, 0x1
            goto :cond_loop
            :done
            # ── OK BillingResult ──
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v0
            invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            # ── QueryProductDetailsResult.create(list, emptyList) ──
            new-instance v1, Ljava/util/ArrayList;
            invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
            invoke-static {v2, v1}, Lcom/android/billingclient/api/QueryProductDetailsResult;->create(Ljava/util/List;Ljava/util/List;)Lcom/android/billingclient/api/QueryProductDetailsResult;
            move-result-object v1
            # ── p2.onProductDetailsResponse(OK, result) ──
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ProductDetailsResponseListener;->onProductDetailsResponse(Lcom/android/billingclient/api/BillingResult;Lcom/android/billingclient/api/QueryProductDetailsResult;)V
            return-void
            nop
        """.trimIndent())
    }
}

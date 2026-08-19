package app.missiles.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.missiles.patches.shared.Constants.COMPATIBILITY_MISSILES
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors. The \$ escapes keep Kotlin string interpolation from
// treating "$ProductDetailsParams" / "$Product" / "$Builder" as template expressions.
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"

/**
 * Missiles Billing Bypass — Google Play Billing 8.0.0 purchase spoof
 * (pl.macaque.Missiles v1.41, Unity IL2CPP + Unity IAP 4.14.2 → Play Billing Java).
 *
 * Strategy: spoof the trusted source. The game's C# Unity IAP layer talks to the
 * *Java* BillingClient (bitter/jnibridge proxies), so faking the Java-side
 * callbacks is indistinguishable from a real Play purchase from C#'s point of
 * view. No Google Play sheet ever opens; C# grants the product synchronously.
 *
 * Four hooks on com.android.billingclient.api.BillingClientImpl (classes.dex,
 * public API names — never obfuscated; verified against smali):
 *   1. launchBillingFlow            → build a fake Purchase for the tapped SKU,
 *                                     deliver via this.zzf → zzs.zzd() → onPurchasesUpdated(OK, [purchase]),
 *                                     return OK. PRIMARY — this IS the purchase grant.
 *   2. acknowledgePurchase          → p2.onAcknowledgePurchaseResponse(OK) + return (no real service).
 *   3. consumeAsync                 → p2.onConsumeResponse(OK, p1.getPurchaseToken()) + return.
 *   4. queryProductDetailsAsync     → fake ProductDetails per SKU read from the params
 *                                     (p1.zza() list → Product.zza()=id / Product.zzb()=type),
 *                                     QueryProductDetailsResult.create(list, empty), p2.onProductDetailsResponse(OK, result).
 *
 * SKU-agnostic: no hardcoded product catalog. Product ids/types are extracted
 * dynamically from the BillingFlowParams / QueryProductDetailsParams at runtime.
 *
 * RISKS (documented from target-hunter notes + smali verification):
 * - zz* version lock: BillingFlowParams.zzk()/zzj(), BillingFlowParams$ProductDetailsParams.zza(),
 *   QueryProductDetailsParams.zza(), QueryProductDetailsParams$Product.zza()/zzb(),
 *   BillingClientImpl.zzf, zzs.zzd() are Google's own R8-minified names shipped in
 *   the billing 8.0.0 AAR — stable for this version, MUST be re-verified if the
 *   billing lib version changes. Everything else uses public API only.
 * - IL2CPP callback expectations: spoofed callbacks fire synchronously on the
 *   calling (main) thread. Unity IAP's GooglePlayStoreBillingClient queues
 *   onPurchasesUpdated events internally — this is the known-working pattern
 *   from the ad-layer patches. If C# hangs at the Buy button, deliver via
 *   activity.runOnUiThread (mimic ad layer pattern).
 * - ack/consume MUST be patched together with launchBillingFlow: C# calls them
 *   immediately after the purchase; if they hit the real service, Play rejects
 *   the fake token and Unity IAP may mark the transaction failed.
 * - Purchase signature: ctor arg 2 is the signature string (iput to zzb, read by
 *   getSignature()) — passing "" is correct; Unity IAP does not verify signatures
 *   client-side on the GooglePlay path (server-side only).
 * - Native validation: if the game validates receipts server-side or via
 *   getPurchaseHistoryList, the spoof is detectable. Current evidence (no
 *   server-call strings in metadata, standard Unity IAP flow) suggests
 *   client-side trust — flag for in-game testing.
 *
 * Register budgets verified against smali:
 * - launchBillingFlow:        .registers 33 → 30 locals (p0=v30,p1=v31,p2=v32); injected body is a
 *                             tiny straight-line delegate to the injected morpheFakePurchase helper
 *                             (NO labels/goto — required, see WHY below). p0/p2 are marshaled into
 *                             v0/v1 via move-object/from16 because {p0,p2}={v30,v32} is unencodable
 *                             in a single invoke (35c max v15; 3rc needs a contiguous range).
 * - morpheFakePurchase:       INJECTED HELPER — private final instance method on BillingClientImpl,
 *                             .registers 9 → 7 locals (p0=this=v7, p1=BillingFlowParams=v8);
 *                             body uses v0-v6, p0 (this, for the zzf field) and p1 (BillingFlowParams
 *                             — the original body's p2, renumbered because the helper drops the
 *                             Activity parameter).
 * - acknowledgePurchase:      .registers 9  → 6 locals (p2=v8);           body uses v0-v1.
 * - consumeAsync:             .registers 9  → 6 locals (p1=v7,p2=v8);     body uses v0-v1.
 * - queryProductDetailsAsync: .registers 9  → 6 locals (v0-v5, exactly);  loop body fits exactly
 *                             (v0 StringBuilder, v1 list, v2 accumulator, v3 index, v4/v5 temps).
 *
 * WHY the helper exists: BillingClientImpl.launchBillingFlow carries a LARGE try/catch exception
 * table. Morphe's instruction-tree writer DROPS every labeled block (goto / branch targets) when
 * merging injected code into a method that has catches — the original labeled injection landed
 * TRUNCATED at the first branch (deployer verified at baksmali + string-pool level: only the
 * leading BillingResult-OK build materialized, then the method fell through to the original
 * Random/new-instance flow; the fake-Purchase JSON, Purchase ctor, onPurchasesUpdated delivery
 * and return-object were all absent). A freshly injected method has NO exception table, so its
 * labeled :sku_old/:sku_done body merges intact — hence morpheFakePurchase.
 */
@Suppress("unused")
val missilesBillingBypassPatch = bytecodePatch(
    name = "Missiles Billing Bypass",
    description = "Spoofs the Google Play Billing 8.0.0 purchase flow: every buy is instantly granted (fake Purchase delivered through the registered listener), purchases/consumes complete instantly, and the product catalog is served with fake ProductDetails — no Google Play sheet, no real payment.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MISSILES)

    execute {
        // ═══ 1. launchBillingFlow — INSTANT PURCHASE GRANT (primary) ══════════
        // WHY the helper (deployer-verified root cause): launchBillingFlow carries a LARGE
        // try/catch exception table. Morphe's instruction-tree writer DROPS every labeled block
        // (goto / branch targets) when merging injected code into a method that has catches —
        // the original labeled injection landed truncated after the BillingResult-OK build and
        // fell through to the original Random/new-instance flow. A freshly injected method has
        // NO exception table, so the labeled SKU-read (:sku_old/:sku_done) and fake-Purchase
        // body is moved there. launchBillingFlow's injected entry is then a tiny straight-line
        // delegate — no labels, no branches → safe against the exception-table merger.
        //
        // Inject the helper FIRST (private instance on the same class; needs `this` for the
        // zzf field; own register budget .registers 9 -> p0=v7 (this), p1=v8 (BillingFlowParams),
        // locals v0-v6), then swap launchBillingFlow's entry for the invoke-direct delegate
        // (p0=v30, p2=v32 marshaled into v0/v1 via /from16 — see the delegate comment below).
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
                # ── 1. BillingResult OK (persisted in v0) ──
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

                # ── 2. SKU from p1 (BillingFlowParams): new-style zzk() (Unity IAP 4.x) ──
                # p1 = the original launchBillingFlow body's p2 — renumbered because the helper
                # drops the Activity parameter (this=p0, BillingFlowParams=p1).
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

                # ── 2b. fallback: old-style BillingFlowParams.zzj() → SkuDetails.getSku() ──
                :sku_old
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v1, v2}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, Lcom/android/billingclient/api/SkuDetails;
                invoke-virtual {v2}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                move-result-object v2

                # ── 3. Fake Purchase JSON: <sku> + UNIQUE token/orderId + now + acknowledged:false ──
                # ROOT-CAUSE FIX (billing-currency.md §2): Unity IAP dedups purchases by
                # token/orderId (WasPurchaseAlreadyProcessed / m_TransactionLog). The old FIXED
                # identity made the 2nd+ purchase a "duplicate" → no OnPurchaseSucceeded, no grant.
                # ONE System.currentTimeMillis() is captured in v4/v5 (wide) and appended at THREE
                # sites — purchaseToken suffix, orderId prefix, purchaseTime — so every fake purchase
                # is a distinct transaction. Dedicated ack/consume hooks still complete the purchase.
                # FOLLOW-UP (not this round): hook queryPurchasesAsync with a per-SKU → unique-token
                # map (billing-currency.md §6 FIX 3) for restore/restart persistence.
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
                const-string v6, "-1234-5678-9012-34567\",\"purchaseState\":0,\"packageName\":\"pl.macaque.Missiles\",\"purchaseTime\":"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v6, ",\"acknowledged\":false}"
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v4

                # ── 4. new Purchase(json, "") — arg2 is the signature (zzb), not product type ──
                const-string v5, ""
                new-instance v6, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v6, v4, v5}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

                # ── 5. Registered listener: this.zzf → zzs.zzd() ──
                iget-object v4, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzf:Lcom/android/billingclient/api/zzs;
                invoke-virtual {v4}, Lcom/android/billingclient/api/zzs;->zzd()Lcom/android/billingclient/api/PurchasesUpdatedListener;
                move-result-object v4

                # ── 6. singleton list ──
                new-instance v5, Ljava/util/ArrayList;
                invoke-direct {v5}, Ljava/util/ArrayList;-><init>()V
                invoke-virtual {v5, v6}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

                # ── 7. deliver + return OK (original flow never reached) ──
                invoke-interface {v4, v0, v5}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                return-object v0
                nop
            """.trimIndent())
        }
        billingClientImplClass.methods.add(morpheFakePurchase)

        // launchBillingFlow entry: delegate to the helper. Straight-line only — the dead original
        // body (with its huge exception table) stays below the return and is never reached.
        // NOTE (deployer-verified): {p0, p2} = {v30, v32} CANNOT be encoded as a direct invoke —
        // dex 35c caps invoke registers at v15 and 3rc (range) requires a CONTIGUOUS register
        // list, so {v30, v32} is unencodable. Marshal both through low locals with explicit
        // /from16 (the inline parser does NOT auto-widen plain move-object; it silently drops
        // invalid moves — T9 repro). v0/v1 are dead locals here: the delegate returns before the
        // original body runs.
        BillingClientImplLaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, Lcom/android/billingclient/api/BillingClientImpl;->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent())

        // ═══ 2. acknowledgePurchase — complete the transaction instantly ══════
        // C# calls this right after the purchase event. Fire the listener with OK
        // and return — the real service would reject the fake token.
        BillingClientImplAcknowledgePurchaseFingerprint.method.addInstructions(0, """
            # v0 = OK BillingResult
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
            # p2.onAcknowledgePurchaseResponse(OK)
            invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
            return-void
        """.trimIndent())

        // ═══ 3. consumeAsync — complete consumables instantly ═════════════════
        // Listener expects (BillingResult, purchaseToken); token read from p1 via
        // the public getPurchaseToken() (verified in the original failure path).
        BillingClientImplConsumeAsyncFingerprint.method.addInstructions(0, """
            # v0 = OK BillingResult
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
            # v1 = p1.getPurchaseToken()
            invoke-virtual {p1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
            move-result-object v1
            # p2.onConsumeResponse(OK, token)
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
            return-void
        """.trimIndent())

        // ═══ 4. queryProductDetailsAsync — fake catalog, SKU-agnostic ═════════
        // Loop over p1.zza() (zzbt, implements java.util.List) of
        // QueryProductDetailsParams$Product; per product read zzb()=type and
        // zza()=productId (verified), build a fake ProductDetails JSON (ctor reads
        // "productId" and "type" — the notes' "productType" draft was WRONG, the
        // real key is "type"), collect into an ArrayList, then deliver
        // p2.onProductDetailsResponse(OK, QueryProductDetailsResult.create(list, empty)).
        // Uses exactly the 6 locals (v0-v5) of .registers 9 — no helper needed.
        BillingClientImplQueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(0, """
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
            const-string v5, "\",\"title\":\"Morphe\",\"name\":\"Morphe\",\"price\":\"${'$'}1.99\",\"priceCurrencyCode\":\"USD\",\"originalPrice\":\"${'$'}1.99\",\"originalPriceAmountMicros\":1990000,\"priceAmountMicros\":1990000}"
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
            const-string v1, "OK"
            invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setDebugMessage(Ljava/lang/String;)$BILLING_RESULT_BUILDER
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

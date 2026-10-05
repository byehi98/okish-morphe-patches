package app.shootyskies.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.shootyskies.patches.shared.Constants.COMPATIBILITY_SHOOTYSKIES
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors. The \$ escapes keep Kotlin string interpolation
// from treating "$Builder" / "$ProductDetailsParams" / "$Product" as template
// expressions. NOTE: the trailing ';' is part of each descriptor — dropping it
// leaves an unterminated type that makes the smali compiler report cascade
// lexer errors several lines downstream.
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"

/**
 * Shooty Skies — Free store (DEX billing forge, Google Play Billing 8.0.0).
 *
 * The game has NO app-owned Java billing bridge: Unity IAP's C# GooglePlay
 * store talks straight to `com.android.billingclient.api.BillingClientImpl`
 * through JNI (every method name below appears in global-metadata.dat).
 * Faking the Java-side callbacks is indistinguishable from a real Play
 * purchase from IL2CPP's point of view — C# then runs its OWN grant funnel
 * (ProcessPurchase → grant → PersistentStorage) with game-owned save code,
 * receipt bookkeeping and analytics intact. No Play sheet ever opens;
 * nothing is charged. Same strategy as the production Crossy Road patch
 * (identical billing 8.0.0, same class/method shapes).
 *
 * Five hooks on BillingClientImpl (public API names — never obfuscated;
 * every body/filter smali-verified in this build, see Fingerprints.kt):
 *
 *  1. queryPurchasesAsync → fabricated owned list containing `remove_ads_iap`
 *     (static GPA.morphe.1 identity). C#'s startup RestorePurchases reads it
 *     → ad removal is granted on EVERY launch without any purchase flow.
 *     PRIMARY — highest leverage, simplest.
 *  2. acknowledgePurchase → p2.onAcknowledgePurchaseResponse(OK) + return.
 *     The fake token must never reach Play (it would be rejected).
 *  3. consumeAsync → p2.onConsumeResponse(OK, original token) + return.
 *     Proven failure mode elsewhere (ITD2): a fake token hitting the real
 *     consumeAsync returns Play error 5 → the grant is silently dropped.
 *  4. launchBillingFlow → fake Purchase for the TAPPED product, delivered on
 *     the SDK's own path (this.zzf → zzs.zzd() → onPurchasesUpdated(OK, …)),
 *     then return OK. This IS the buy button — data-driven shop SKUs resolve
 *     live from BillingFlowParams, nothing hardcoded.
 *  5. queryProductDetailsAsync → fabricate a ProductDetails entry for EVERY
 *     queried product id (price $4.99), so the store renders and buy buttons
 *     enable even if Play rejects the repackaged signature
 *     (DEVELOPER_ERROR / SERVICE_UNAVAILABLE).
 *
 * WHY the launchBillingFlow helper (proven BurritoBison/Missiles regression
 * root cause): launchBillingFlow has a LARGE exception table (26
 * `.catch`/`.catchall` directives, 136 labels — verified in this build).
 * Morphe's instruction-tree writer DROPS labeled blocks when merging labeled
 * injections into a method with catches — the body lands truncated and falls
 * through to the original (real) flow. A freshly injected method has NO
 * exception table, so the labeled SKU-read + fake-Purchase body merges
 * intact; launchBillingFlow's own injected entry is a tiny straight-line
 * delegate (no labels/branches) — catch-safe. The other four targets have
 * ZERO catches (verified) → direct injection is safe.
 *
 * Register budgets (all verified against this build's smali):
 *  - queryPurchasesAsync:  `.registers 9` → p0=v6, p1=v7, p2=v8; body uses v0-v3.
 *  - acknowledgePurchase:  `.registers 9` → p2=v8; body uses v0-v1 + p2.
 *  - consumeAsync:         `.registers 9` → p1=v7, p2=v8; body uses v0-v1 + p1/p2.
 *  - queryProductDetailsAsync: `.registers 9` → p0=v6, p1=v7, p2=v8; body uses
 *                          v0-v5 exactly (accumulator in v2 survives to delivery).
 *  - launchBillingFlow:    `.registers 33` → p0=v30, p1=v31, p2=v32. Delegate
 *                          marshals p0/p2 into v0/v1 via /from16 ({v30,v32} is
 *                          unencodable: 35c caps at v15, 3rc needs a contiguous
 *                          range). v0/v1 are dead locals: the delegate returns
 *                          before the original (catch-tabled) body runs.
 *  - morpheFakePurchase:   INJECTED `.registers 9` → p0=this=v7,
 *                          p1=BillingFlowParams=v8, locals v0-v6 — every
 *                          invoke register ≤ v15.
 *
 * Product id extraction order (deviation note): notes say the legacy
 * `zzj()` (SkuDetails) list must NOT be used for this app — C# builds
 * BillingFlowParams via `setProductDetailsParamsList` (→ `zzk()` →
 * ProductDetailsParams), so that is the PRIMARY path here. The zzj read is
 * kept only as a null-guarded FALLBACK so a SkuDetails-built flow (or a
 * future Unity IAP swap) still resolves; if both are empty the purchase is
 * delivered as `unknown_sku` — a safe no-grant, never a crash. All zz* and Sku
 * names are Google's own for the pinned 8.0.0 library (version-coupled).
 *
 * Identity strategy (matches premium-bypass.md):
 *  - queryPurchasesAsync uses a STATIC identity (GPA.morphe.1 /
 *    morphe-token) — deterministic across restarts, exactly what the notes
 *    prescribe for the restore grant.
 *  - launchBillingFlow is unique PER TAP (one System.currentTimeMillis →
 *    orderId + purchaseTime + purchaseToken) so Unity IAP's transaction
 *    dedup never swallows the 2nd+ purchase of a consumable.
 *  - Purchase ctor arg 2 is the signature — `""` is correct (Unity IAP does
 *    not verify signatures client-side on the GooglePlay store; the ctor
 *    only stores it).
 *
 * P6 (catalog) feasibility is Med-Low per the notes: the JSON shape must
 * satisfy C#'s ProductDetails parsing — it is copied from the production
 * Crossy Road patch (same library version) which proved that key set, but a
 * runtime pass (logcat) should confirm which fields Unity IAP consumes.
 * Receipt-side validation (`ValidateReceipt`) is a C#-side open question —
 * if the game validates signatures server-side/native-side, the grant needs
 * the native companion path (P7), which is out of scope here.
 */
@Suppress("unused")
val shootySkiesFreeStorePatch = bytecodePatch(
    name = "Shooty Skies Free store",
    description = "Every store item is free — tap \"Buy\" and the purchase completes instantly with no Google Play payment, and owned items like ad removal are granted at startup.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SHOOTYSKIES)

    execute {
        // ═══ 1. queryPurchasesAsync — restore / startup entitlement ═════════
        // Fabricated owned list. Static identity; straight-line body (no
        // labels) — safe to inject directly at the entry of a catch-free
        // method. Granted SKU: remove_ads_iap (the only known SKU string in
        // global-metadata.dat; shop SKUs are data-driven — the P5 buy flow
        // covers them live by product id).
        QueryPurchasesAsyncFingerprint.method.addInstructions(0, """
            # v0 = ArrayList<Purchase> containing the fabricated owned item
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
            new-instance v1, Lcom/android/billingclient/api/Purchase;
            const-string v2, "{\"orderId\":\"GPA.morphe.1\",\"packageName\":\"com.mightygamesgroup.shootyskies\",\"productId\":\"remove_ads_iap\",\"productIds\":[\"remove_ads_iap\"],\"purchaseState\":0,\"purchaseTime\":0,\"purchaseToken\":\"morphe-token\",\"quantity\":1,\"acknowledged\":true}"
            const-string v3, ""
            invoke-direct {v1, v2, v3}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
            invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            # v1 = BillingResult.OK
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
            move-result-object v1
            const/4 v2, 0x0
            invoke-virtual {v1, v2}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
            move-result-object v1
            invoke-virtual {v1}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v1
            invoke-interface {p2, v1, v0}, Lcom/android/billingclient/api/PurchasesResponseListener;->onQueryPurchasesResponse(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
            return-void
        """.trimIndent())

        // ═══ 2. acknowledgePurchase — durables complete instantly ═══════════
        // Straight-line body (no labels), catch-free method → direct injection.
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

        // ═══ 3. consumeAsync — consumables (coin packs) complete instantly ══
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

        // ═══ 4. launchBillingFlow — INSTANT PURCHASE GRANT (buy button) ═════
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

                # ── 2. Tapped product id from p1 (BillingFlowParams) ──
                #      primary: zzk() → List<ProductDetailsParams> (the API C#
                #      builds via setProductDetailsParamsList) → ProductDetailsParams
                #      .zza() → ProductDetails.getProductId().
                #      fallback: zzj() → ArrayList<SkuDetails> → getSku(), so a
                #      SkuDetails-built flow still resolves.
                #      zzby.zza(iterable, null) = first element or null (verified),
                #      v2 doubles as the null sentinel (still 0 from step 1).
                const-string v4, "unknown_sku"
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
                move-result-object v1
                invoke-static {v1, v2}, Lcom/google/android/gms/internal/play_billing/zzby;->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, $BFP_PRODUCT_DETAILS_PARAMS
                if-eqz v1, :morphe_legacy
                invoke-virtual {v1}, $BFP_PRODUCT_DETAILS_PARAMS->zza()Lcom/android/billingclient/api/ProductDetails;
                move-result-object v1
                if-eqz v1, :morphe_legacy
                invoke-virtual {v1}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
                move-result-object v4
                goto :morphe_json

                :morphe_legacy
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v1
                invoke-static {v1, v2}, Lcom/google/android/gms/internal/play_billing/zzby;->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lcom/android/billingclient/api/SkuDetails;
                if-eqz v1, :morphe_json
                invoke-virtual {v1}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                move-result-object v4

                # ── 3. Fake Purchase JSON — unique per tap (one currentTimeMillis,
                #      captured in v2 as a digit string, appended at orderId,
                #      purchaseTime and purchaseToken) so Unity IAP's transaction
                #      dedup never swallows repeat purchases. Both productId and
                #      productIds are emitted (Purchase.getProducts() reads the
                #      array, falling back to the scalar — verified) ──
                :morphe_json
                invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                move-result-wide v2
                invoke-static {v2, v3}, Ljava/lang/String;->valueOf(J)Ljava/lang/String;
                move-result-object v2
                new-instance v1, Ljava/lang/StringBuilder;
                invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
                const-string v3, "{\"orderId\":\"GPA.morphe."
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\",\"packageName\":\"com.mightygamesgroup.shootyskies\",\"productId\":\""
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\",\"productIds\":[\""
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\"],\"purchaseTime\":"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, ",\"purchaseState\":0,\"purchaseToken\":\"morphe-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v3, "\",\"quantity\":1,\"acknowledged\":true}"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v5

                # ── 4. new Purchase(json, "") — arg2 is the signature ("" = no
                #      client-side signature verification, ctor only stores it) ──
                const-string v2, ""
                new-instance v3, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v3, v5, v2}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

                # ── 5. Registered listener: this.zzf → zzs.zzd(), null-guarded
                #      so a pre-connect call returns OK instead of NPE-ing ──
                iget-object v1, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzf:Lcom/android/billingclient/api/zzs;
                if-eqz v1, :morphe_done
                invoke-virtual {v1}, Lcom/android/billingclient/api/zzs;->zzd()Lcom/android/billingclient/api/PurchasesUpdatedListener;
                move-result-object v1
                if-eqz v1, :morphe_done
                invoke-static {v3}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v2
                invoke-interface {v1, v0, v2}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V

                :morphe_done
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

        // ═══ 5. queryProductDetailsAsync — fake price catalog ═══════════════
        // Loop p1.zza() → zzbt (implements List — verified `.implements
        // Ljava/util/List;`) of QueryProductDetailsParams$Product; per product
        // read zza()=productId and zzb()=type, so EVERY product the game asks
        // about (data-driven shop SKUs included) gets a fake price entry.
        // ProductDetails ctor requires non-empty "productId"/"type"; PRICES are
        // read ONLY from the nested "oneTimePurchaseOfferDetailsList" (preferred;
        // wins when present) or the "oneTimePurchaseOfferDetails" object — flat
        // top-level price keys are ignored, which would leave
        // getOneTimePurchaseOfferDetails() null → buy buttons stuck in
        // PendingIAPData → store shows its generic connection-error text.
        // Nested keys are all opt* (no JSONException): formattedPrice,
        // priceAmountMicros (number), priceCurrencyCode.
        // Uses exactly the 6 locals (v0-v5) of .registers 9; method has no catch
        // table (verified), so direct labeled injection is safe; its only
        // existing label is :cond_34 — no collision with :cond_loop/:done.
        QueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(0, """
            # p0=this(v6), p1=QueryProductDetailsParams(v7), p2=ProductDetailsResponseListener(v8)
            # locals: v0 StringBuilder / OK result scratch, v1 product list / result,
            #         v2 ArrayList accumulator, v3 loop index, v4/v5 temps
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
            # the ctor ignores top-level price keys and only builds
            # OneTimePurchaseOfferDetails from "oneTimePurchaseOfferDetailsList"
            # (array, preferred — ctor returns early when non-null) or the
            # "oneTimePurchaseOfferDetails" object fallback; both are emitted
            # (identical) so Java getters and any JSON-string consumer see price.
            const-string v5, "\",\"title\":\"Morphe\",\"name\":\"Morphe\",\"price\":\"${'$'}4.99\",\"priceCurrencyCode\":\"USD\",\"originalPrice\":\"${'$'}4.99\",\"originalPriceAmountMicros\":4990000,\"priceAmountMicros\":4990000,\"oneTimePurchaseOfferDetails\":{\"formattedPrice\":\"${'$'}4.99\",\"priceAmountMicros\":4990000,\"priceCurrencyCode\":\"USD\"},\"oneTimePurchaseOfferDetailsList\":[{\"formattedPrice\":\"${'$'}4.99\",\"priceAmountMicros\":4990000,\"priceCurrencyCode\":\"USD\"}]}"
            invoke-virtual {v0, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4
            # new ProductDetails(json) — package-private ctor, same package → legal;
            # ctor requires non-empty productId + type (both read live above)
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

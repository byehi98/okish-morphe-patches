package app.burritobison3.patches.iap

import app.burritobison3.patches.shared.Constants.COMPATIBILITY_BURRITO_BISON
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors. The \$ escapes keep Kotlin string interpolation from
// treating "$Builder" / "$ProductDetailsParams" as template expressions.
// NOTE: the trailing ';' is part of each descriptor — dropping it leaves an
// unterminated type that makes InlineSmaliCompiler report cascade lexer errors
// on '/' several lines downstream (seen as: parser+lexer errors on the ->build() line).
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"

/**
 * Single purchase patch for Burrito Bison. Covers every Java layer the Unity
 * (IL2CPP) game consults before granting a paid item:
 *
 *  - Kongregate MTX entitlement query (inventory impl hasItem + Unity bridge
 *    KongregateAPIMtxHasItem) → always reports ownership.
 *  - Kongregate receipt verification → always VALID, and the Google receipt
 *    response-signature (RSA) check always passes, so forged/empty receipts
 *    validate. (Note: the code already fails open with VALID on HTTP
 *    connection/server errors — blocking the validate host has the same
 *    effect without patching.)
 *  - Google Play Billing launchBillingFlow() → tapping buy NEVER opens the
 *    Google Play sheet; an OK BillingResult plus a forged Purchase for the
 *    requested SKU is delivered to the registered PurchasesUpdatedListener.
 *    The forged receipt flows into the normal Unity IAP →
 *    KongregateAPIMtxVerifyGooglePurchase → /mobile_receipt/validate_google
 *    path, where the receipt→VALID + RSA bypass + hasItem→true above turn it
 *    into ownership.
 *
 * CAVEAT (per analysis): the inventory map is server-populated
 * (/api/user_items.json). Items the game never queries via hasItem — or that
 * are enforced purely inside libil2cpp.so — are unaffected.
 *
 * Play Billing implementation notes (v3 fixed shape, same as
 * MissilesBillingBypassPatch / TrafficRiderBillingBypassPatch):
 * the forged-purchase body lives in an injected helper (morpheFakePurchase,
 * .registers 9 → p0=v7 this, p1=v8 BillingFlowParams, locals v0-v6), and the
 * launchBillingFlow entry is a tiny straight-line delegate (no labels, no
 * branches) that marshals the high params low and returns. The original body
 * (with its large try/catch table) stays below the return as dead code.
 *
 * WHY the helper (v2 regression root cause — deployer-verified):
 *  1. Register encoding: the target has .registers 31 (p0=v28, p1=v29,
 *     p2=v30). Dex 35c invokes (invoke-virtual/interface/static/direct) can
 *     only encode v0-v15, so a direct `invoke-virtual {p2} ...zzj/zzk`
 *     assembled to `Invalid register: v30. Must be between v0 and v15`, and the
 *     original smali itself uses `invoke-virtual/range {p2 .. p2}` plus an early
 *     `move-object/from16 v9, p0` for exactly this reason.
 *  2. Exception-table label-drop: launchBillingFlow spans smali 8454-10557 with
 *     several catch handlers (:catch_644..:catchall_694). Morphe's
 *     instruction-tree writer drops every labeled block when merging labeled
 *     injections (:play_sku/:play_pdp/:play_json/:play_done) into a method with
 *     catches — the body lands truncated at the first branch and falls through
 *     to the original Random/new-instance flow with clobbered registers. Every
 *     billing call (including startup init) then misbehaves → Unity IAP init
 *     hangs on the "Launcha Libre" screen (no crash, Kongregate panel fine,
 *     asset-bundle URL null because init never completes).
 *  The helper has NO exception table, so its labeled SKU-read + JSON + delivery
 *  body merges intact; the entry delegate is straight-line and catch-safe.
 *
 * Helper body (all regs encodable — v0-v6 locals, p0=v7, p1=v8):
 *  (a) builds an OK BillingResult via BillingResult.newBuilder()
 *      .setResponseCode(OK).build() into v0 (live throughout);
 *  (b) extracts the requested SKU from p1 (BillingFlowParams) with the same
 *      accessors the original uses (zzj() → SkuDetails.getSku() first per this
 *      billing version's smali 8543-8571 order, fallback zzk() →
 *      ProductDetailsParams.zza() → ProductDetails.getProductId()), null-safe
 *      via the SDK's own zzby.zza(Iterable, null) helper;
 *  (c) forges one Purchase via the public Purchase(String, String) ctor
 *      with minimal valid JSON (orderId / packageName / productId /
 *      purchaseTime / purchaseState=0 / purchaseToken / quantity /
 *      acknowledged / autoRenewing, unique per tap via currentTimeMillis) and
 *      an empty signature;
 *  (d) delivers it via this.zzf.zzd().onPurchasesUpdated(OK, list) — the
 *      exact iget/zzd/invoke-interface sequence from the SDK's own dispatch
 *      site (smali 1429-1439) — with null guards so a pre-connect call simply
 *      returns OK instead of NPE-ing; then returns the OK result.
 *
 * Fingerprint pins definingClass + name + exact (Activity, BillingFlowParams)
 * params, so ONLY user-initiated launchBillingFlow is affected — never
 * queryPurchasesAsync / queryProductDetailsAsync / startConnection init paths.
 */
@Suppress("unused")
val burritoBisonFreePurchasePatch = bytecodePatch(
    name = "Free purchases",
    description = "Get paid items for free — tapping buy grants the item instantly without Google Play payment popup."
) {
    compatibleWith(COMPATIBILITY_BURRITO_BISON)

    execute {
        // ── 1. Kongregate MTX entitlement query always reports ownership ──
        // Choke point: inventory lookup always reports ownership.
        MtxHasItemFingerprint.method.returnEarly(true)
        // Fallback one layer up: Unity bridge passthrough always true.
        UnityMtxHasItemFingerprint.method.returnEarly(true)

        // ── 2. Receipt verification always VALID + RSA response-sig passes ──
        // valueOf() always yields VALID. p0 (the input String) is reused as
        // the return register — safe regardless of the method's register
        // count since we return immediately without reading the argument.
        ReceiptValueOfFingerprint.method.addInstructions(
            0,
            """
                sget-object p0, Lcom/kongregate/android/api/MicrotransactionServices${'$'}ReceiptVerificationStatus;->VALID:Lcom/kongregate/android/api/MicrotransactionServices${'$'}ReceiptVerificationStatus;
                return-object p0
            """
        )
        // RSA response-signature check always passes.
        GoogleResponseSigFingerprint.method.returnEarly(true)

        // ── 3. Play Billing: never open the Play sheet, forge the purchase ──
        // ═══ Helper: full forged-purchase body in a catch-free method ═══
        // .registers 9 → 7 locals (v0-v6) + p0=v7 (this, for the zzf field) +
        // p1=v8 (BillingFlowParams — the original body's p2, renumbered because
        // the helper drops the Activity parameter). Every invoke below uses
        // only v0-v6/p0/p1, all ≤ v15, so every 35c encoding is legal.
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
                # ── 2. SKU from p1: old-style zzj() → SkuDetails first (this billing version's smali order) ──
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v1
                invoke-static {v1, v2}, Lcom/google/android/gms/internal/play_billing/zzby;->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lcom/android/billingclient/api/SkuDetails;
                if-nez v1, :play_sku
                # ── 2b. fallback: new-style zzk() → ProductDetailsParams → ProductDetails.getProductId() ──
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
                # ── 3. Fake Purchase JSON: unique per tap via one currentTimeMillis captured in v2(wide low)/v3 ──
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
                const-string v3, "\",\"packageName\":\"com.kongregate.mobile.burritobison3.google\",\"productId\":\""
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
                # ── 4. new Purchase(json, "") — arg2 is the signature, "" is correct (no client-side verify) ──
                const-string v2, ""
                new-instance v3, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v3, v5, v2}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
                # ── 5. Registered listener: this.zzf → zzs.zzd(), null-guarded so pre-connect calls return OK ──
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

        // ═══ launchBillingFlow entry: delegate to the helper (straight-line, catch-safe) ═══
        // NOTE: {p0, p2} = {v28, v30} CANNOT be encoded as a direct invoke —
        // dex 35c caps invoke registers at v15 and 3rc (range) requires a
        // CONTIGUOUS register list, so {v28, v30} is unencodable (this was the
        // v2 "Invalid register: v30/v28" failure). Marshal both through low
        // locals with explicit /from16 (the inline parser does NOT auto-widen
        // plain move-object). v0/v1 are dead locals here: the delegate returns
        // before the original body runs.
        LaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, Lcom/android/billingclient/api/BillingClientImpl;->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent())
    }
}

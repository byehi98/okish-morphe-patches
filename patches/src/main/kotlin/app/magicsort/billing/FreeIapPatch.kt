package app.magicsort.billing

import app.magicsort.shared.AbiAnchorSet
import app.magicsort.shared.Constants.COMPATIBILITY_MAGIC_SORT
import app.magicsort.shared.Constants.PACKAGE_NAME
import app.magicsort.shared.NativeAnchor
import app.magicsort.shared.applyLibil2cppEdits
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
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
private const val BILLING_CLIENT_IMPL = "Lcom/android/billingclient/api/BillingClientImpl;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"
private const val BILLING_RESULT = "Lcom/android/billingclient/api/BillingResult;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val PURCHASE = "Lcom/android/billingclient/api/Purchase;"
private const val PURCHASES_UPDATED_LISTENER = "Lcom/android/billingclient/api/PurchasesUpdatedListener;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val LIST = "Ljava/util/List;"

/**
 * Magic Sort 0.0.3480 — **Free IAP** (Java Billing forge + native grant-branch hardening).
 *
 * Two layers, one listing entry. Neither half is useful alone:
 *  - the Java hook is the *source* of the fake `Purchase` — without it the game
 *    still waits for Google Play;
 *  - the native branches make the C# flow unconditionally take its local-grant
 *    path, so no server-side remote-config flag can route the purchase into the
 *    blocking `ProcessPurchaseWithVerificationAsync`.
 *
 * There is no receipt validation to defeat on either layer:
 * `CrossPlatformValidator.Validate` (`0x1EABB1C`) has **zero xrefs**, its ctor is
 * never called, and no Google RSA public-key prefix exists in the string table;
 * `IAPManager.InitializePurchasing` (`0x13885A4`) is the stock
 * `ConfigurationBuilder` path with no validator. A synthetic Java `Purchase`
 * therefore reaches `IAPManager.ProcessPurchase` with nothing in between.
 *
 * ══ PART 1 — NATIVE: force the local-grant branch in `IAPManager.ProcessPurchase` ══
 *
 * `IAPManager.ProcessPurchase` has three branches, and only the legacy one blocks.
 * Both ABIs are patched; the branch structure is identical, the encodings are not.
 *
 * arm64-v8a — `ProcessPurchase` @ `0x146C0A0`, local grant @ `0x146C114`:
 * ```
 * 0x146C0DC  tbnz w0, #0, #0x146C114   ; after ShouldProceedWithoutLegacyVerification
 * 0x146C0F8  ldrb w8, [x8, #0x30]      ; RootLevelConfigModel.IsAndroidIapVerificationEnabled
 * 0x146C0FC  cbz  w8, #0x146C114
 * 0x146C100  mov  x0, x20
 * 0x146C108  bl   #0x146C480           ; ProcessPurchaseWithVerificationAsync  <- BLOCKING
 * 0x146C114  mov  x0, x20
 * 0x146C11C  bl   #0x146C3D8           ; ProcessPurchaseWithoutVerification     <- local grant
 * ```
 * Rewritten to the unconditional `b 0x146C114` (`0e 00 00 14` at `0x146C0DC`,
 * `06 00 00 14` at `0x146C0FC`).
 *
 * armeabi-v7a — `ProcessPurchase` @ `0xC1210C`, local grant @ `0xC1222C`
 * (ARM/A32 code, not Thumb):
 * ```
 * 0xC121D0  bl   #0xC1932C             ; proceeds-without-legacy check
 * 0xC121D4  cmp  r0, #0
 * 0xC121D8  beq  #0xC1222C             ; -> local grant
 * 0xC1220C  ldrb r0, [r6, #0x28]       ; IsAndroidIapVerificationEnabled
 * 0xC12210  cmp  r0, #0
 * 0xC12214  beq  #0xC1222C
 * 0xC12220  bl   #0xC12734             ; ProcessPurchaseWithVerificationAsync  <- BLOCKING
 * 0xC1222C  bl   #0xC12648             ; ProcessPurchaseWithoutVerification     <- local grant
 * ```
 * Both `beq` are rewritten to the unconditional `b 0xC1222C`
 * (`13 00 00 EA` at `0xC121D8`, `04 00 00 EA` at `0xC12214`).
 *
 * The flow therefore always reaches `ProcessPurchaseWithoutVerification`. That
 * function makes **no network call at all**: it calls `PurchaseResult.Success()` and
 * completes the `TaskCompletionSource`; downstream
 * `ApplyPurchaseSuccessCommand.ExecuteAsync` (`0x2B3E0E4`… in 0.0.3480) calls
 * `RewardService.GiveDirectlyRewards`, records the purchase in
 * `InAppPurchaseHistoryService` and calls `SyncService.TrySyncAllData` *after* the
 * grant. `IsVerified` is consumed only to decide whether to send an Adjust analytics
 * event — it never gates the reward. Server verification
 * (`IAPVerificationRemoteService`) is enqueued after the local grant and fails open
 * after 5 attempts, so it cannot revoke anything.
 *
 * Do **NOT** patch `IAPVerificationService.IsEnabled` (`0x146C134`) → `false`: with
 * `IsEnabled == false` the code falls through to reading the server-controlled
 * legacy flag, which can be `true` and land in the blocking
 * `ProcessPurchaseWithVerificationAsync`. Also do **NOT** patch
 * `RootLevelConfigModel.get_IsAndroidIapVerificationEnabled` (`0x1B41874`) — it is
 * never called; the flag is read as a raw field at `0x146C0F8` (`0xC1220C` on
 * armeabi-v7a), which is why the branch is the correct edit.
 *
 * ══ PART 2 — JAVA: `com.android.billingclient.api.BillingClientImpl` (Billing 8.3.0) ══
 *
 * Lives in **classes4.dex**. Four hooks on public SDK methods (stable names, verified
 * in analysis/magicsort/smali_billing/):
 *   1. `launchBillingFlow` — forge an OK `BillingResult` + a fake `Purchase` for the
 *      tapped SKU, deliver it via `this.zzf → zzab.zzf() → onPurchasesUpdated(OK, …)`,
 *      and return OK. This IS the grant: no Play sheet ever opens.
 *   2. `acknowledgePurchase` — non-consumables finish with an OK callback.
 *   3. `consumeAsync` — consumable coin packs finish with OK + the token.
 *   4. `queryProductDetailsAsync` — fake catalogue so the store populates and the buy
 *      buttons enable even with no working Play catalog fetch.
 *
 * 8.3.0 facts (all re-checked in the extracted smali, not assumed):
 *  - `zzf` is `.field private volatile zzf:Lcom/android/billingclient/api/zzab;`
 *    and the `PurchasesUpdatedListener` comes from **`zzab->zzf()`**
 *    (`zzab.smali:115`). `zzab->zzd()` returns a `DeveloperProvidedBillingListener`
 *    — passing that to `onPurchasesUpdated` throws `ClassCastException`. The helper
 *    mirrors the real body's two null checks (`BillingClientImpl.smali` launch flow).
 *  - `launchBillingFlow` is `.registers 30` and carries a large try/catch table
 *    (20 `.catch`/`.catchall` handlers). Morphe's instruction writer drops labelled
 *    blocks when merging into a method with catches, so the forged-purchase body
 *    lives in a freshly injected helper (`.registers 12` → v0..v9, p0=v10,
 *    p1=v11) with NO exception table, and the real method only gets a label-free
 *    5-instruction delegate at index 0.
 *  - `acknowledgePurchase` / `consumeAsync` / `queryProductDetailsAsync` are
 *    `.registers 9` (p0=v6, p1=v7, p2=v8) and have **no** exception table, so a
 *    straight-line body is injected directly. `queryProductDetailsAsync` does carry
 *    one original label (`:cond_34`); our labels are uniquely named and the injected
 *    block ends in `return-void`, leaving the original body as unreachable code.
 *  - `BillingFlowParams->zzk()Ljava/util/List;` (line 1020) is the product source in
 *    8.3.0 (the `SkuDetails` API is gone). Elements cast to
 *    `BillingFlowParams$ProductDetailsParams` → `zza()ProductDetails` →
 *    `getProductId()`.
 *  - `QueryProductDetailsParams->zza()` returns
 *    **`Lcom/google/android/gms/internal/play_billing/zzbw;`** in 8.3.0 (9.0.0 used
 *    `zzca`). `zzbw` is `abstract … implements java.util.List`, so the catalogue is
 *    walked with `Collection.iterator()` — verifier-safe regardless of the concrete
 *    collection the builder produced. `QueryProductDetailsParams$Product` gives
 *    `zza()` = productId and `zzb()` = type.
 *  - `QueryProductDetailsResult->create(List, List)` and
 *    `ProductDetailsResponseListener->onProductDetailsResponse(BillingResult,
 *    QueryProductDetailsResult)` exist.
 *
 * The `ProductDetails` JSON **must** nest the price under
 * `"oneTimePurchaseOfferDetails"` with `formattedPrice`, `priceAmountMicros`,
 * `priceCurrencyCode` (and `offerId`). `ProductDetails.<init>` parses exactly those
 * keys; a flat `price` block parses “successfully” but leaves the offer details
 * `null`, which NREs in the game's price UI (the same failure the Crash of Cars
 * patch documents on-device).
 *
 * The fake purchase JSON uses the keys Unity IAP reads — `productId`,
 * `purchaseToken`, `orderId`, `purchaseState`, `packageName`, `purchaseTime`,
 * `acknowledged` — with `packageName = com.grandgames.magicsort`. The second
 * `Purchase` ctor argument is the signature and is `""`: Magic Sort has no
 * client-side signature validation (`CrossPlatformValidator.Validate` is dead), so
 * the empty signature is correct. Token/order/time are derived from one
 * `System.currentTimeMillis()` per tap so repeat purchases are never deduplicated.
 *
 * **`queryPurchasesAsync` is deliberately NOT patched** — a delta from the Crash of
 * Cars template. Magic Sort keeps its entitlements in its own local
 * `InAppPurchaseHistoryService` (the same service the Remove Ads patch reads), so a
 * fake owned-purchases list adds nothing at launch and would actually *hide*
 * legitimate restores by replacing the real Play response with synthetic items.
 * Likewise `PurchasesResponseListener` is not touched.
 *
 * ══ WHY one entry: inline dependsOn(rawResourcePatch) ═════════════════════
 *
 * The DSL binds exactly one context type per Patch subclass (javap-verified against
 * morphe-patcher 1.5.2): `bytecodePatch` → `BytecodePatchContext`, which has no
 * `get(path, uncompress)` file API; only `ResourcePatchContext` (`rawResourcePatch`)
 * exposes it. The native half therefore cannot live in this patch's own `execute {}`.
 * Creating the `rawResourcePatch` **inline inside `dependsOn(...)`** keeps exactly
 * one discovered listing entry, because PatchLoader discovers patches only through
 * public static top-level fields / zero-arg methods returning `Patch` — an inline
 * dependency is never a field. The dependency's `execute` runs first (resource
 * extraction + `.so` write), then this patch's Java hooks run; the halves touch
 * disjoint artifacts (classes4.dex vs `lib/arm64-v8a/libil2cpp.so`), so order is
 * irrelevant beyond that.
 */
@Suppress("unused")
val freeIapPatch = bytecodePatch(
    name = "Free IAP",
    description = "Every in-app purchase completes instantly — coin packs, bundles and the no-ads offer — with no Google Play payment sheet.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAGIC_SORT)

    // ══ Native grant-branch hardening — inline rawResourcePatch dependency ══
    // Defined INSIDE dependsOn() so PatchLoader never discovers it as a separate
    // top-level entry (see KDoc "WHY one entry").
    dependsOn(
        rawResourcePatch(
            name = "Free IAP (grant branches)",
            description = "Every in-app purchase completes instantly — coin packs, bundles and the no-ads offer — with no Google Play payment sheet.",
            default = true,
        ) {
            compatibleWith(COMPATIBILITY_MAGIC_SORT)

            execute {
                applyLibil2cppEdits(
                    patchName = "Free IAP (grant branches)",
                    // Both anchors are mandatory per ABI — they cover the legacy-check
                    // and IsAndroidIapVerificationEnabled entry conditions respectively;
                    // either one alone leaves a path into the blocking verification flow.
                    anchorSets = listOf(
                        AbiAnchorSet(
                            "arm64-v8a",
                            listOf(SKIP_LEGACY_VERIFICATION_ARM64, FORCE_LOCAL_GRANT_ARM64),
                        ),
                        AbiAnchorSet(
                            "armeabi-v7a",
                            listOf(SKIP_LEGACY_VERIFICATION_V7A, FORCE_LOCAL_GRANT_V7A),
                        ),
                    ),
                )
            }
        },
    )

    execute {
        // ── Fail loudly rather than ship a partially patched purchase flow ──────
        val fingerprintMatches = listOf(
            "launchBillingFlow" to LaunchBillingFlowFingerprint.matchAllOrNull(),
            "acknowledgePurchase" to AcknowledgePurchaseFingerprint.matchAllOrNull(),
            "consumeAsync" to ConsumeAsyncFingerprint.matchAllOrNull(),
            "queryProductDetailsAsync" to QueryProductDetailsAsyncFingerprint.matchAllOrNull(),
        )
        val problems = fingerprintMatches.mapNotNull { (name, matches) ->
            val count = matches?.size ?: 0
            if (count == 1) null else "$name matched $count time(s), expected exactly 1"
        }
        if (problems.isNotEmpty()) {
            throw PatchException(
                "Magic Sort Free IAP: ${problems.joinToString("; ")}. The app's Play " +
                    "Billing (8.3.0) / BillingClientImpl layout has changed — refusing to " +
                    "emit a partially patched APK.",
            )
        }

        // ═══ 1. launchBillingFlow — INSTANT GRANT (primary hook) ══════════════
        // The forged-purchase body lives in an injected helper (no exception table →
        // labels merge intact); the original launchBillingFlow body stays below the
        // delegate's return as dead code.
        //
        // Helper `.registers 12` → locals v0..v9, p0=v10 (this), p1=v11
        // (BillingFlowParams).
        //   v0 = OK BillingResult (held to the end)
        //   v1 = product list from zzk(), then scratch
        //   v2 = size / index / ProductDetailsParams / ProductDetails / productId
        //   v3 = StringBuilder
        //   v4,v5 = currentTimeMillis() wide pair — EXCLUSIVE, never an arg register
        //   v6 = json String, then the zzab holder, then the listener
        //   v7 = empty signature ""
        //   v8 = Purchase
        //   v9 = ArrayList<Purchase>
        val billingClientImplClass = LaunchBillingFlowFingerprint.classDef
        val morpheFakePurchase = ImmutableMethod(
            BILLING_CLIENT_IMPL,
            "morpheFakePurchase",
            listOf(
                ImmutableMethodParameter(
                    "Lcom/android/billingclient/api/BillingFlowParams;",
                    null,
                    null,
                ),
            ),
            BILLING_RESULT,
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(12),
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
                #    Billing 8.3.0: SkuDetails is gone, so zzk() is the only product
                #    source. Its list is always populated by the constructor.
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
                #    Billing 8.3.0: field zzf is a zzab and the listener is zzf(),
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

        // 5-instruction, label-free delegate in the real method. The original
        // (catch-tabled) body stays untouched below the return.
        // {p0, p2} = {v27, v29} of .registers 30 — marshal through v0/v1 with
        // explicit /from16 (the inline parser does NOT auto-widen plain
        // move-object), because {v27, v29} is unencodable as one invoke.
        LaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, $BILLING_CLIENT_IMPL->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)$BILLING_RESULT
            move-result-object v0
            return-object v0
        """.trimIndent())

        // ═══ 2. acknowledgePurchase — non-consumables finish here ═══
        // .registers 9 → locals v0..v5, p0=v6, p1=v7, p2=v8 (listener).
        // Straight-line (no labels, no catch table) → direct entry injection.
        AcknowledgePurchaseFingerprint.method.addInstructions(0, """
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

        // ═══ 3. consumeAsync — consumable coin packs finish here ═══
        // .registers 9 → p0=v6, p1=v7 (ConsumeParams), p2=v8 (listener).
        ConsumeAsyncFingerprint.method.addInstructions(0, """
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
        // The method has one original label (:cond_34) but NO exception table, so
        // addInstructionsWithLabels is safe here (Missiles/Crossy pattern).
        //
        // `.registers 9` → locals v0..v5, p0=v6, p1=v7, p2=v8 (listener).
        //   v0 = OK BillingResult (held to the end)
        //   v1 = Iterator over zza(), then the empty List for create()
        //   v2 = ArrayList<ProductDetails>
        //   v3 = hasNext() flag, then the current Product
        //   v4 = StringBuilder, then the json String
        //   v5 = string constant, then the ProductDetails instance
        //
        // At the :morphe_loop back-edge v3/v4/v5 are still undefined on the entry
        // path, so the int-vs-reference reuse of v3 merges cleanly (no verifier
        // conflict) and nothing is read there.
        //
        // ⚠️ The price MUST be nested under `oneTimePurchaseOfferDetails` — see the
        // patch KDoc. A flat price block parses but leaves the offer details null.
        QueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(0, """
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

            # Billing 8.3.0: zza() returns play_billing/zzbw (abstract, implements
            # java.util.List), not 9.0.0's zzca. Walk it as a Collection —
            # Collection.iterator() is guaranteed by the type hierarchy.
            invoke-virtual {p1}, Lcom/android/billingclient/api/QueryProductDetailsParams;->zza()Lcom/google/android/gms/internal/play_billing/zzbw;
            move-result-object v1
            invoke-interface {v1}, Ljava/util/Collection;->iterator()Ljava/util/Iterator;
            move-result-object v1

            :morphe_loop
            invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
            move-result v3
            if-eqz v3, :morphe_done

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
            const-string v5, "\",\"title\":\"Magic Sort\",\"name\":\"Magic Sort\",\"description\":\"Magic Sort\",\"oneTimePurchaseOfferDetails\":{\"formattedPrice\":\"${'$'}1.99\",\"priceAmountMicros\":1990000,\"priceCurrencyCode\":\"USD\",\"offerId\":\"\"}}"
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4
            new-instance v5, Lcom/android/billingclient/api/ProductDetails;
            invoke-direct {v5, v4}, Lcom/android/billingclient/api/ProductDetails;-><init>(Ljava/lang/String;)V
            invoke-virtual {v2, v5}, $ARRAY_LIST->add(Ljava/lang/Object;)Z
            goto :morphe_loop

            :morphe_done
            new-instance v1, $ARRAY_LIST
            invoke-direct {v1}, $ARRAY_LIST-><init>()V
            invoke-static {v2, v1}, Lcom/android/billingclient/api/QueryProductDetailsResult;->create($LIST$LIST)Lcom/android/billingclient/api/QueryProductDetailsResult;
            move-result-object v1
            invoke-interface {p2, v0, v1}, Lcom/android/billingclient/api/ProductDetailsResponseListener;->onProductDetailsResponse($BILLING_RESULT Lcom/android/billingclient/api/QueryProductDetailsResult;)V
            return-void
            nop
        """.trimIndent())

        // NOTE: queryPurchasesAsync is intentionally NOT hooked — Magic Sort's
        // entitlements are local (InAppPurchaseHistoryService); a fake owned-purchases
        // list adds nothing and would mask legitimate restores. See patch KDoc.
    }
}

/**
 * `IAPManager.ProcessPurchase` @ `0x146C0DC` (arm64-v8a, 0.0.3480):
 * `tbnz w0,#0,#0x146C114` → `b 0x146C114` (`c0 01 00 37` → `0e 00 00 14`).
 *
 * Verify window (24 B, one hit in 95,838,880 B): the `tbnz` followed by the
 * `ldr x8,[x20,#0x38]` / … remote-config chain that starts the legacy branch.
 */
private val SKIP_LEGACY_VERIFICATION_ARM64 = NativeAnchor(
    label = "IAPManager.ProcessPurchase -> always ProcessPurchaseWithoutVerification (batch-verification branch)",
    rva = 0x146C0DC,
    fileOffset = 0x146C0DC,
    verifyHex = "c0010037881e40f9680200b4080d40f9280200b4080940f9",
    replacementHex = "0e000014",
)

/**
 * `IAPManager.ProcessPurchase` @ `0x146C0FC` (arm64-v8a, 0.0.3480):
 * `cbz w8,#0x146C114` → `b 0x146C114` (`c8 00 00 34` → `06 00 00 14`).
 *
 * Verify window (24 B, one hit): the `cbz` followed by
 * `mov x0,x20; mov x1,x19; bl ProcessPurchaseWithVerificationAsync; mov w0,#1; b …`
 * — i.e. the blocking legacy call we are skipping.
 */
private val FORCE_LOCAL_GRANT_ARM64 = NativeAnchor(
    label = "IAPManager.ProcessPurchase -> always ProcessPurchaseWithoutVerification (legacy-config branch)",
    rva = 0x146C0FC,
    fileOffset = 0x146C0FC,
    verifyHex = "c8000034e00314aae10313aade0000942000805205000014",
    replacementHex = "06000014",
)

/**
 * `IAPManager.ProcessPurchase` @ `0xC121D8` (armeabi-v7a, 0.0.3480):
 * `beq 0xC1222C` → `b 0xC1222C`
 * (`13 00 00 0a` → `13 00 00 ea`; A32 `b` imm24 = (0xC1222C − 0xC121D8 − 8)/4 = 0x13).
 *
 * Verify window (24 B, one hit in 86,265,520 B): the `beq` followed by the field
 * dereference chain (`ldr r6,[r5,#0x1c]` …) that begins the legacy branch.
 */
private val SKIP_LEGACY_VERIFICATION_V7A = NativeAnchor(
    label = "IAPManager.ProcessPurchase -> always ProcessPurchaseWithoutVerification (batch-verification branch)",
    rva = 0xC121D8,
    fileOffset = 0xC121D8,
    verifyHex = "1300000a1c6095e5000056e30000001ad2aef7eb0c6096e5",
    replacementHex = "130000ea",
)

/**
 * `IAPManager.ProcessPurchase` @ `0xC12214` (armeabi-v7a, 0.0.3480):
 * `beq 0xC1222C` → `b 0xC1222C`
 * (`04 00 00 0a` → `04 00 00 ea`; imm24 = (0xC1222C − 0xC12214 − 8)/4 = 0x04).
 *
 * Verify window (24 B, one hit): the `beq` followed by
 * `mov r0,r5; mov r1,r4; bl ProcessPurchaseWithVerificationAsync; mov r0,#1; pop` —
 * the blocking legacy call this edit skips. The flag itself is read at `0xC1220C`
 * (`ldrb r0,[r6,#0x28]`), so patching the getter would do nothing.
 */
private val FORCE_LOCAL_GRANT_V7A = NativeAnchor(
    label = "IAPManager.ProcessPurchase -> always ProcessPurchaseWithoutVerification (legacy-config branch)",
    rva = 0xC12214,
    fileOffset = 0xC12214,
    verifyHex = "0400000a0500a0e10410a0e1430100eb0100a0e3f088bde8",
    replacementHex = "040000ea",
)

/**
 * `BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult`.
 *
 * Public Billing API — the name, parameters and `public` flag are stable across
 * billing-library updates (the body shape is not). Filters pin the 8.3.0 sequence in
 * exact smali order: `zzab->zzf()` (listener) → `getDeveloperBillingOptionParams()` →
 * `BillingFlowParams->zzj()` → `zzk()` → `ProductDetails->getProductId()`.
 */
private object LaunchBillingFlowFingerprint : Fingerprint(
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf(
        "Landroid/app/Activity;",
        "Lcom/android/billingclient/api/BillingFlowParams;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = BILLING_CLIENT_IMPL,
    name = "launchBillingFlow",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzab;",
            name = "zzf",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "getDeveloperBillingOptionParams",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "zzj",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "zzk",
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/ProductDetails;",
            name = "getProductId",
        ),
    ),
)

/**
 * `BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams,
 * AcknowledgePurchaseResponseListener)V`.
 *
 * `.registers 9`, no exception table. The exact parameter list plus the public flag
 * and this class already make it unique; `zzK` (the SDK's own timeout helper) is
 * matched as a structural sanity check.
 */
private object AcknowledgePurchaseFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = BILLING_CLIENT_IMPL,
    name = "acknowledgePurchase",
    filters = listOf(
        methodCall(
            definingClass = BILLING_CLIENT_IMPL,
            name = "zzK",
        ),
    ),
)

/**
 * `BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V`.
 *
 * `.registers 9`, no exception table. `ConsumeParams->getPurchaseToken()` is read in
 * the body and is also the value the hook echoes back to the listener.
 */
private object ConsumeAsyncFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = BILLING_CLIENT_IMPL,
    name = "consumeAsync",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/ConsumeParams;",
            name = "getPurchaseToken",
        ),
    ),
)

/**
 * `BillingClientImpl.queryProductDetailsAsync(QueryProductDetailsParams,
 * ProductDetailsResponseListener)V`.
 *
 * `.registers 9`, one original label, NO exception table. The real body builds a
 * `QueryProductDetailsResult` directly, which pins this method structurally.
 */
private object QueryProductDetailsAsyncFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryProductDetailsParams;",
        "Lcom/android/billingclient/api/ProductDetailsResponseListener;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = BILLING_CLIENT_IMPL,
    name = "queryProductDetailsAsync",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/QueryProductDetailsResult;",
            name = "<init>",
        ),
    ),
)

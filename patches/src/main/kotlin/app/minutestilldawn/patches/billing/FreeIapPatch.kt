package app.minutestilldawn.patches.billing

import app.minutestilldawn.patches.pairip.pairipBypassPatch
import app.minutestilldawn.patches.shared.Constants.COMPATIBILITY_20MTD
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableBooleanEncodedValue

// Smali descriptors. The \$ escapes stop Kotlin from treating $Builder /
// $ProductDetailsParams as template expressions.
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val QUERY_PRODUCT = "Lcom/android/billingclient/api/QueryProductDetailsParams\$Product;"

private const val BILLING_CLIENT_IMPL = "Lcom/android/billingclient/api/BillingClientImpl;"
private const val BRIDGE = "Lcom/youka/iapplugin/OneTimeOfferBridge;"
private const val PACKAGE_NAME = "com.Flanne.MinutesTillDawn.roguelike.shooting.fr.gp"

// ── Google Play Billing 8.3.0 (source tag `billing@@8.3.0`) ──────────────────
// Targets are public Play Billing API methods on BillingClientImpl — they are
// never obfuscated and are unique by (name, params) inside the class.

/** `launchBillingFlow(Activity, BillingFlowParams)BillingResult` — `.locals 27` → `.registers 30`, p0=v27, p1=v28, p2=v29. */
private object LaunchBillingFlowFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "launchBillingFlow",
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf("Landroid/app/Activity;", "Lcom/android/billingclient/api/BillingFlowParams;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
)

/** `.locals 6` → `.registers 9`, p0=v6, p1=v7, p2=v8. */
private object AcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "acknowledgePurchase",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
)

/** `.locals 6` → `.registers 9`, p1=v7, p2=v8. */
private object ConsumeAsyncFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "consumeAsync",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
)

/** `.locals 6` → `.registers 9`, p0=v6, p1=v7, p2=v8. No try/catch → labels are safe. */
private object QueryProductDetailsAsyncFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "queryProductDetailsAsync",
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryProductDetailsParams;",
        "Lcom/android/billingclient/api/ProductDetailsResponseListener;",
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
)

/**
 * `startConnection(BillingClientStateListener)V` — `.locals 1`, exact-match flags omitted
 * because the method is `public` but not `final`.
 *
 * Without this hook the whole billing client never leaves DISCONNECTED on a device with no
 * Play Store: `startConnection` times out, the listener receives a non-OK result, and
 * `queryProductDetailsAsync` / `launchBillingFlow` are never called at all — which is exactly
 * what "the IAP does nothing" looks like. Reporting OK here is what makes the other four
 * hooks reachable.
 */
private object StartConnectionFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "startConnection",
    returnType = "V",
    parameters = listOf("Lcom/android/billingclient/api/BillingClientStateListener;"),
)

/** `isReady()Z` — `.locals 1`. Unity IAP gates queries on this. */
private object IsReadyFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "isReady",
    returnType = "Z",
    parameters = emptyList(),
)

/** `getConnectionState()I` — `.locals 2`. 2 = `BillingClient.ConnectionState.CONNECTED`. */
private object ConnectionStateFingerprint : Fingerprint(
    definingClass = BILLING_CLIENT_IMPL,
    name = "getConnectionState",
    returnType = "I",
    parameters = emptyList(),
)

/**
 * Free in-app purchases — spoofs the Java side of Google Play Billing so every
 * purchase is granted instantly and no Play payment sheet ever opens.
 *
 * 20 Minutes Till Dawn is a Unity IL2CPP title; both of its shops go through the
 * *Java* Play Billing client, not through a bespoke bridge:
 *
 *  - Unity IAP (C#) → `com.android.billingclient.api.BillingClient` proxies.
 *  - The Youka one-time-offer bridge (`com.youka.iapplugin.OneTimeOfferBridge`)
 *    creates its own `BillingClient`, queries with `queryProductDetailsAsync`
 *    and then calls `launchBillingFlow` from
 *    `lambda$launchPurchaseFlow$1`. Both shops therefore land on
 *    `BillingClientImpl`, so one patch covers them.
 *
 * Seven hooks on `BillingClientImpl` (all public API names, version-stable). The first
 * three exist so the other four are ever reached on a device without Play:
 *
 *  0. `startConnection` / `isReady` / `getConnectionState` — report CONNECTED immediately.
 *     Without these the client stays DISCONNECTED, Unity IAP refuses to query, and no
 *     purchase code ever runs (the classic "IAP does nothing" symptom).
 *
 *  1. `launchBillingFlow` — never contacts Play. Builds a forged `Purchase` for
 *     the tapped SKU and delivers it synchronously to the registered
 *     `PurchasesUpdatedListener` (`this.zzf` → `zzab.zzf()`), then returns OK.
 *     This *is* the grant: C# sees a normal successful purchase callback.
 *  2. `acknowledgePurchase` — fires the listener with OK (Unity IAP calls this
 *     immediately after the purchase; the real service would reject a fake token).
 *  3. `consumeAsync` — same for consumables, echoing the token from the params.
 *  4. `queryProductDetailsAsync` — serves a synthetic catalog derived from the
 *     ids/types in the request, so the shop populates and the Buy buttons enable
 *     even though Play knows nothing about this install.
 *
 * SKU-agnostic: no product catalog is hardcoded — ids and types are read from
 * the request objects at runtime (`coin_20000`, `coin_110000`,
 * `coin_110000_first_purchase`, `coin_250000`, `coin_540000`, `coin_1500000`,
 * `coin_1500000_first_purchase`, `oneyear_battlepass`, `oneyear_giftpack`,
 * `oneyear_trial`, `oneyear_wish`, `oneyear_celebration`, …).
 *
 * ## Why the forged purchase lives in an injected helper
 *
 * `BillingClientImpl.launchBillingFlow` carries a large try/catch table, and
 * Morphe's instruction-tree writer **drops labelled blocks** when merging
 * injected code into a method that has catches (deployer-verified on sibling
 * apps: the body landed truncated at the first branch and fell through into the
 * original `Random`/`new-instance` flow with clobbered registers). A freshly
 * injected method has no exception table, so its labelled SKU-read and
 * fake-Purchase body merges intact. `launchBillingFlow` gets only a tiny
 * straight-line delegate (no labels, no branches).
 *
 * Register encoding also forces the delegate: `.registers 30` puts p0 at v27 and
 * p2 at v29, and dex 35c invokes can only encode v0–v15 (3rc needs a *contiguous*
 * range, and {v27, v29} is not contiguous). The Activity and BillingFlowParams
 * are therefore marshalled into low locals with explicit `move-object/from16`
 * — the inline smali parser does not auto-widen a plain `move-object`.
 *
 * ## Per-purchase uniqueness
 *
 * Unity IAP dedups transactions by `purchaseToken`/`orderId`. Every forged
 * purchase appends a fresh `System.currentTimeMillis()`, so a second purchase of
 * the same SKU is not treated as a duplicate (which would silently swallow the
 * grant). `acknowledgePurchase` / `consumeAsync` then complete the transaction.
 *
 * ## Verified zz* names for 8.3.0 (Google's own R8 output — version locked)
 *
 *  - `BillingFlowParams.zzk()Ljava/util/List;` → `ProductDetailsParams`
 *  - `BillingFlowParams.zzj()Ljava/util/ArrayList;` → `SkuDetails` (legacy)
 *  - `BillingFlowParams$ProductDetailsParams.zza()` → `ProductDetails`
 *  - `QueryProductDetailsParams.zza()` → `zzbw` (abstract, `implements java.util.List`)
 *  - `QueryProductDetailsParams$Product.zza()` = id, `.zzb()` = type
 *  - `BillingClientImpl.zzf` → `zzab`, `zzab.zzf()` → `PurchasesUpdatedListener`
 *
 * ## Limits
 *
 * The purchase *verifier* is in `libil2cpp.so` (`localIAPVerify`, `IAPManager`,
 * `CheckReceiptValidation`, `OnCheckEntitlement`) and cannot be reached from
 * DEX. This patch forges the Java billing boundary only. There is no `no_ads`
 * SKU, and `SignatureInfoTool` has zero call sites (dead code, not a gate).
 */
@Suppress("unused")
val freeIapPatch = bytecodePatch(
    name = "Free in-app purchases",
    description = "Every shop purchase is granted instantly — no Google Play payment sheet, no real charge. Covers coins, packs, the one-year battle pass and the First Purchase / Ad-Free Forever bundles, which are claimed by tapping Buy once like any other product."
) {
    compatibleWith(COMPATIBILITY_20MTD)

    dependsOn(pairipBypassPatch)

    execute {
        // ═══ Forged-purchase helper (catch-free, own register budget) ═══════════
        // .registers 12 → locals v0-v9, p0 = v10 (this), p1 = v11 (BillingFlowParams).
        // Every register used is ≤ v15, so all 35c encodes are legal.
        val billingClientImplClass = LaunchBillingFlowFingerprint.classDef
        val fakePurchase = ImmutableMethod(
            BILLING_CLIENT_IMPL,
            "morpheFakePurchase",
            listOf(ImmutableMethodParameter("Lcom/android/billingclient/api/BillingFlowParams;", null, null)),
            "Lcom/android/billingclient/api/BillingResult;",
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(12),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    # ── v0 = BillingResult OK (kept live to the end) ──
                    invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                    move-result-object v1
                    const/4 v2, 0x0
                    invoke-virtual {v1, v2}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                    move-result-object v1
                    invoke-virtual {v1}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                    move-result-object v0

                    # ── v2 = requested product id (new-style zzk first) ──
                    invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
                    move-result-object v1
                    if-eqz v1, :sku_old
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

                    # ── legacy path: zzj() → SkuDetails.getSku() ──
                    :sku_old
                    invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                    move-result-object v1
                    const/4 v3, 0x0
                    invoke-virtual {v1, v3}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
                    move-result-object v2
                    check-cast v2, Lcom/android/billingclient/api/SkuDetails;
                    invoke-virtual {v2}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                    move-result-object v2

                    # ── forged Purchase JSON: unique orderId/token per tap ──
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

                    const-string v6, "-1234-5678-9012-34567\",\"purchaseState\":0,\"packageName\":\"$PACKAGE_NAME\",\"purchaseTime\":"
                    invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;

                    const-string v6, ",\"quantity\":1,\"acknowledged\":false,\"autoRenewing\":false}"
                    invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                    move-result-object v6

                    # ── new Purchase(json, "") ────────────────────────────────────
                    # The signature is deliberately empty. Sauce only skips receipt
                    # validation when the signature is missing ("No signature available
                    # for local validation, skipping validation"); a fake signature
                    # makes it attempt a real verification instead, which can never
                    # pass. The Unity-IAP shortfall is handled by the bridge replay
                    # below, not by faking a signature.
                    const-string v7, ""
                    new-instance v8, Lcom/android/billingclient/api/Purchase;
                    invoke-direct {v8, v6, v7}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

                    # ── deliver to the registered listener (null-guarded) ──
                    iget-object v6, p0, Lcom/android/billingclient/api/BillingClientImpl;->zzf:Lcom/android/billingclient/api/zzab;
                    if-eqz v6, :fake_done
                    invoke-virtual {v6}, Lcom/android/billingclient/api/zzab;->zzf()Lcom/android/billingclient/api/PurchasesUpdatedListener;
                    move-result-object v6
                    if-eqz v6, :fake_done

                    new-instance v7, Ljava/util/ArrayList;
                    invoke-direct {v7}, Ljava/util/ArrayList;-><init>()V
                    invoke-virtual {v7, v8}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                    invoke-interface {v6, v0, v7}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V

                    # ── replay through the game's own Youka bridge ────────────────
                    # Only the shop items actually unlock: they are bought through
                    # OneTimeOfferBridge.PurchaseWithOffer, whose native path logs
                    # "No signature available for local validation, skipping
                    # validation" and grants. Everything else (First Purchase, hero
                    # and soulstone packs) is bought through Unity IAP, where
                    # Sauce.IAPManager:CheckReceiptValidation rejects the forged
                    # receipt with "Invalid receipt, not unlocking content" and the
                    # UI reports `Shop BuyFailed`. Replaying the same product id
                    # through the bridge routes it down the validation-free path.
                    # Shop purchases set morpheFromBridge in PurchaseWithOffer, so
                    # they are skipped here to avoid granting twice.
                    sget-boolean v1, Lcom/youka/iapplugin/OneTimeOfferBridge;->morpheFromBridge:Z
                    # morpheFromBridge == 0 → Unity IAP purchase → replay needed.
                    # morpheFromBridge == 1 → already went through PurchaseWithOffer,
                    # which grants on its own, so do not grant twice.
                    if-eqz v1, :fake_replay
                    const/4 v1, 0x0
                    sput-boolean v1, Lcom/youka/iapplugin/OneTimeOfferBridge;->morpheFromBridge:Z
                    goto :fake_done

                    :fake_replay
                    invoke-static {v2}, Lcom/youka/iapplugin/OneTimeOfferBridge;->morpheUnlockNative(Ljava/lang/String;)V

                    :fake_done
                    return-object v0
                    nop
                """.trimIndent(),
            )
        }
        billingClientImplClass.methods.add(fakePurchase)

        // ═══ Youka-bridge replay target ════════════════════════════════════════
        // The game's own billing wrapper skips receipt validation whenever the
        // purchase carries no signature, so replaying a product id through it is
        // the only way to unlock the products that are otherwise bought through
        // Unity IAP (whose Sauce-side validation always fails on a forged receipt).
        val bridgeClass = mutableClassDefBy(BRIDGE)

        // Set from PurchaseWithOffer so morpheFakePurchase can tell the two
        // purchase origins apart and avoid granting shop items twice.
        bridgeClass.staticFields.add(
            ImmutableField(
                BRIDGE,
                "morpheFromBridge",
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                ImmutableBooleanEncodedValue.forBoolean(false),
                null,
                null,
            ).toMutable()
        )

        // purchaseWithOffer(...) is `.locals 8`, so v0 is free at entry.
        bridgeClass.methods.firstOrNull { it.name == "PurchaseWithOffer" }
            ?.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    sput-boolean v0, Lcom/youka/iapplugin/OneTimeOfferBridge;->morpheFromBridge:Z
                """.trimIndent(),
            )
            ?: error("OneTimeOfferBridge.PurchaseWithOffer not found — free-IAP patch out of sync")

        // Builds the bridge's success envelope and hands it to Unity, exactly like
        // the real onPurchasesUpdated path does, then lets C# run its native
        // (validation-free) purchase handling. `.registers 12` -> p0 = v11.
        bridgeClass.methods.add(
            ImmutableMethod(
                BRIDGE,
                "morpheUnlockNative",
                listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(12),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                        move-result-wide v0

                        # v2 = inner "originalJson", pre-escaped for embedding
                        new-instance v2, Ljava/lang/StringBuilder;
                        invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
                        const-string v3, "{\\\"productId\\\":\\\""
                        invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        const-string v3, "\\\",\\\"purchaseToken\\\":\\\"morphe-"
                        invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v2, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                        const-string v3, "\\\",\\\"orderId\\\":\\\"GPA."
                        invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v2, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                        const-string v3, "-1234-5678-9012-34567\\\",\\\"purchaseState\\\":0,\\\"packageName\\\":\\\"$PACKAGE_NAME\\\",\\\"purchaseTime\\\":"
                        invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v2, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                        const-string v3, ",\\\"quantity\\\":1,\\\"acknowledged\\\":true,\\\"autoRenewing\\\":false}"
                        invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                        move-result-object v2

                        # v4 = outer envelope
                        new-instance v4, Ljava/lang/StringBuilder;
                        invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V
                        const-string v3, "{\"responseCode\":0,\"debugMessage\":\"\",\"purchases\":[{\"orderId\":\"GPA."
                        invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v4, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                        const-string v3, "-1234-5678-9012-34567\",\"packageName\":\"$PACKAGE_NAME\",\"purchaseTime\":"
                        invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v4, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                        const-string v3, ",\"purchaseState\":1,\"purchaseToken\":\"morphe-"
                        invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v4, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                        const-string v3, "\",\"signature\":\"\",\"originalJson\":\""
                        invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v4, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        const-string v3, "\",\"productIds\":[\""
                        invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v4, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        const-string v3, "\"]}],\"success\":true}"
                        invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                        invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                        move-result-object v4

                        # The game passes "GameObject" as the Unity object and
                        # "OnPurchaseResult" as the method in its own native flow.
                        const-string v5, "GameObject"
                        const-string v6, "OnPurchaseResult"
                        invoke-static {v5, v6, v4}, Lcom/unity3d/player/UnityPlayer;->UnitySendMessage(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                        return-void
                    """.trimIndent(),
                )
            }
        )

        // ═══ Bundle ids (First Purchase + Ad-Free Forever) ════════════════════
        // Both bundles live in the Unity IAP catalog rather than the shop's
        // `FetchAllOffers` id list, and their ids exist only in the Unity asset
        // catalog (`20mtd_remove_ads` -> `title_ad_free`, `first_purchase_gift` ->
        // `first_time_recharge`) — never in global-metadata. They are not granted
        // up front (the C# `ShopStyle` table does not exist at launch, so a replay
        // there is dropped); tapping either bundle runs the normal purchase path and
        // the bridge replay above grants it for free like any other product.

        // ═══ Connection: pretend Play is installed and connected ═══════════════
        // `zzdc.zzi` is Play Billing's own static BillingResult with response code 0 (OK);
        // it is used instead of building one so the injected body needs a single register
        // (startConnection is `.locals 1`). Delivering OK synchronously drives Unity IAP /
        // the Youka bridge straight into queryProductDetailsAsync.
        StartConnectionFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Lcom/android/billingclient/api/zzdc;->zzi:Lcom/android/billingclient/api/BillingResult;
                invoke-interface {p1, v0}, Lcom/android/billingclient/api/BillingClientStateListener;->onBillingSetupFinished(Lcom/android/billingclient/api/BillingResult;)V
                return-void
            """.trimIndent(),
        )

        // `.locals 1`, no params.
        IsReadyFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """.trimIndent(),
        )

        // `.locals 2`, no params. 2 = BillingClient.ConnectionState.CONNECTED.
        ConnectionStateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x2
                return v0
            """.trimIndent(),
        )

        // ═══ launchBillingFlow: straight-line delegate (no labels, catch-safe) ══
        LaunchBillingFlowFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                move-object/from16 v1, p2
                invoke-direct {v0, v1}, Lcom/android/billingclient/api/BillingClientImpl;->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)Lcom/android/billingclient/api/BillingResult;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )

        // ═══ acknowledgePurchase: complete instantly ═══════════════════════════
        AcknowledgePurchaseFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v0
                invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v0
                invoke-interface {p2, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
                return-void
            """.trimIndent(),
        )

        // ═══ consumeAsync: complete consumables instantly ══════════════════════
        ConsumeAsyncFingerprint.method.addInstructions(
            0,
            """
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
            """.trimIndent(),
        )

        // ═══ queryProductDetailsAsync: synthetic catalog, SKU-agnostic ═════════
        // Exactly the six locals of `.registers 9`:
        //   v0 StringBuilder, v1 product list / BillingResult, v2 accumulator,
        //   v3 loop index, v4/v5 temps.
        //
        // `offerIdToken` and `validTimeWindow` in the catalog JSON are load-bearing: the Youka
        // bridge republishes them to C# as `offerToken` / `startTimeMillis` / `endTimeMillis`, and
        // the C# `OneTimeOfferInfo` + `GetAllValidOffers`/`GetOfferInfo` chain rejects offers that
        // lack them — which is the "PurchaseWithOffer: No valid offer found for productId: ..."
        // dead end this catalog must avoid. Window end = 32503680000000 (year 3000) never expires.
        QueryProductDetailsAsyncFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-virtual {p1}, Lcom/android/billingclient/api/QueryProductDetailsParams;->zza()Lcom/google/android/gms/internal/play_billing/zzbw;
                move-result-object v1
                new-instance v2, Ljava/util/ArrayList;
                invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
                const/4 v3, 0x0
                :catalog_loop
                invoke-interface {v1}, Ljava/util/List;->size()I
                move-result v4
                if-ge v3, v4, :catalog_done

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

                const-string v5, "\",\"title\":\"20 Minutes Till Dawn\",\"name\":\"20 Minutes Till Dawn\",\"packageDisplayName\":\"20 Minutes Till Dawn\",\"iconUrl\":\"\",\"skuDetailsToken\":\"\",\"oneTimePurchaseOfferDetails\":{\"offerIdToken\":\"morphe-offer-token\",\"offerId\":\"morphe-offer\",\"validTimeWindow\":{\"startTimeMillis\":0,\"endTimeMillis\":32503680000000},\"fullPriceMicros\":1990000,\"formattedPrice\":\"${'$'}1.99\",\"priceAmountMicros\":1990000,\"priceCurrencyCode\":\"USD\"}}"
                invoke-virtual {v0, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v4

                new-instance v5, Lcom/android/billingclient/api/ProductDetails;
                invoke-direct {v5, v4}, Lcom/android/billingclient/api/ProductDetails;-><init>(Ljava/lang/String;)V
                invoke-interface {v2, v5}, Ljava/util/List;->add(Ljava/lang/Object;)Z

                add-int/lit8 v3, v3, 0x1
                goto :catalog_loop

                :catalog_done
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
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
            """.trimIndent(),
        )
    }
}

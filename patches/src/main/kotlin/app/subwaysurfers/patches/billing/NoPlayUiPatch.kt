package app.subwaysurfers.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.subwaysurfers.patches.shared.Constants.COMPATIBILITY_SUBWAY_SURFERS
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors containing '$' (inner-class separator). In regular
// strings the \$ escape keeps Kotlin from treating $ as template
// interpolation; interpolated verbatim into the injected raw-string smali
// below (Traffic Rider / Missiles pattern). Verified against smali file
// names: BillingFlowParams$ProductDetailsParams.smali:92 (zza),
// BillingResult$Builder (newBuilder/setResponseCode/setDebugMessage/build).
private const val PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"

/**
 * Subway Surfers v4 — Free Purchase via fabricated billing SUCCESS
 * (no Google Play popup, natural C# success flow, exact pack amounts).
 *
 * com.android.billingclient.api.ProxyBillingActivity is the Play Billing
 * 8.0.0 trampoline that renders the Google Play purchase dialog. v3 armed the
 * activity's cancel path (RESPONSE_CODE=1 "Billing dialog closed." from
 * onDestroy) plus a marker-byte handoff to the native grant engine, so every
 * tap ended as USER_CANCELED + a silent Tier-F 10k grant — wrong amounts, no
 * keys/characters/boards, and a "purchase failed" toast. v4 instead
 * fabricates the NORMAL success result the activity's own onActivityResult
 * would have sent, so the game runs its natural success flow:
 *
 *   tap → launchBillingFlow (real, service-connected) → ProxyBillingActivity
 *   onCreate (body-replaced) → morpheFabricateSuccess() builds a genuine
 *   LOCAL_BROADCAST_PURCHASES_UPDATED success broadcast → finish() →
 *   zzr.onReceive parses it → BillingClientImpl listener chain
 *   (zzbq → nativeOnPurchasesUpdated) → C# onPurchasesUpdated(OK, [Purchase])
 *   → tower validation (hooks A/B force OK) → ProcessPurchasedProduct grants
 *   the EXACT pack the game itself describes (coins, keys, characters…).
 *
 * ── 1. Product id bridge (the launch intent carries NO product id) ────────
 * The BUY intent built at BillingClientImpl.smali:16311 (:cond_619) only
 * receives "BUY_INTENT" (:1579), "billingClientTransactionId" (:1584/:16367
 * putExtra J) and "wasServiceAutoReconnected" (:1591/:16386 putExtra Z) —
 * proven by reading the whole method; there is no productId anywhere.
 * Therefore we inject a 2-instruction STRAIGHT-LINE tag immediately before
 * the startActivity at :16391:
 *
 *   move-object/from16 v12, p2    # p2 = BillingFlowParams (v30; /from16
 *                                 # because 30 is unencodable in 35c)
 *   invoke-static {v0, v12}, …->morpheTagProductId(…)V   # v0 = launch Intent
 *
 * Register liveness (verified against the ART verifier, which rejected the
 * first attempt): launchBillingFlow = .registers 31 (p0=v28, p1=v29,
 * p2=v30). v6:v7 is NOT dead — it is re-written with the transaction id on
 * every path (`move-wide/from16 v6, v26`) and READ back as a long on the
 * error path (`invoke-direct/range {v1 .. v8} …zzbh(…, J, Z)` in the
 * "Exception while launching billing flow" block). Touching v6/v7 inside
 * try_start_63b..try_end_641 makes the catch-handler environment merge
 * Long+Object into Conflict → VerifyError at that zzbh range invoke
 * ("register v6 has type Conflict but expected Long (Low Half)").
 * Registers never read after the insertion point: v12–v23 and v25 — we use
 * v12 (v11 is unsafe: implicit high half of `move-wide v6, v10` at :16610;
 * v7 likewise: long high half, read by the {v1..v8} range invoke).
 * v0 is only READ by our tag (overwritten post-startActivity by
 * `sget-object v0`). Nothing branches INTO the window, so the tag provably
 * executes on every path that reaches startActivity. Inserting between
 * :try_start_63b and :try_end_641 keeps the new code under the existing
 * `catch Exception` — worst case (a get(0) that can't actually fail: build()
 * at BillingFlowParams$Builder.smali:29 throws unless at least one product
 * list is non-empty) degrades to the original error return, never a crash.
 * No labels in the injected block — required, because Morphe's writer drops
 * labeled blocks in methods with exception tables (Missiles/Traffic Rider
 * deployer-verified lesson).
 *
 * morpheTagProductId (fresh private STATIC helper on BillingClientImpl, no
 * exception table → labels are safe) reads the tapped product id via the
 * API the original launchBillingFlow itself uses
 * (BillingFlowParams.smali:1048 zzj / :1075 zzk):
 *   zzk() → non-empty → ProductDetailsParams.zza() (BillingFlowParams$
 *   ProductDetailsParams.smali:92) → ProductDetails.getProductId()
 *   (ProductDetails.smali:713)              — modern path (Unity IAP);
 *   zzj() → non-empty → SkuDetails.getSku() (SkuDetails.smali:551)
 *                                  — legacy path;
 *   both empty (impossible for a real launch) → no extra is written.
 * Builder.build() proves the element types: zzk()=field zze is filled from
 * Builder.zzc List<ProductDetailsParams> (:864/:337, else empty zzbt.zzk()
 * at :342 — never null), zzj()=field zzf is copied from Builder.zzd
 * ArrayList<SkuDetails> (:310-325 — always non-null ArrayList).
 * The result is stored as extra "morphe.productId" (our own key — namespaced
 * so it can never collide with Play's).
 *
 * ── 2. onCreate: fabricate the onActivityResult success broadcast ─────────
 * Body-replace onCreate (ProxyBillingActivity.smali:876, .registers 14;
 * super call :886) with:
 *   super.onCreate(p1) → morpheFabricateSuccess() → finish() → return-void.
 * (Straight-line, no labels — onCreate carries exception table
 * :try_start_9f..:try_end_b3.) The helper (fresh private method, labels OK):
 *   - reads "morphe.productId" back off getIntent(); absent (in-app-message
 *     or first-party launches, which this game's custom store doesn't use)
 *     → return silently, finish() still runs, nothing is broadcast;
 *   - builds one classic Play receipt:
 *       {"orderId":"GPA.morphe.<ts>-<ts>","packageName":"com.kiloo.subwaysurf",
 *        "productId":"…","purchaseState":0,"purchaseToken":"morphe-<ts>",
 *        "purchaseTime":<ts>,"acknowledged":false}
 *   - intent = makePurchasesUpdatedIntent() (the activity's OWN private
 *     helper, :347 — same action/package the real paths use), then adds the
 *     extras the real onActivityResult success path adds
 *     (ProxyBillingActivity.smali:781/:789-807):
 *       RESPONSE_CODE = 0 (int)          — zzc.zzb (zzc.smali:85) reads it;
 *                                          Integer → intValue = 0 = OK
 *       INAPP_PURCHASE_DATA = receipt    — zzc.zzl single path :cond_5a
 *                                          (:1220) → zzq (:1538) parses it
 *       INAPP_DATA_SIGNATURE = "morphe"  — zzq REQUIRES non-null sig; no
 *                                          signature check exists anywhere
 *                                          in the Java layer
 *       INTENT_SOURCE = "LAUNCH_BILLING_FLOW" (fidelity, :792)
 *       billingClientTransactionId (long) — forwarded from the launch
 *                                          intent (:912/:1159 read site)
 *       wasServiceAutoReconnected (bool)  — forwarded (:907/:1200 read site)
 *   - sendBroadcast(intent).
 * DEBUG_MESSAGE is deliberately omitted: zzc.zzj (zzc.smali:921) substitutes
 * "" when the key is missing (logs "assuming OK"), same resulting
 * BillingResult. onDestroy's cancel path never fires because we never set
 * sendCancelledBroadcastIfFinished (default false; onDestroy guard at
 * :1499-:1501).
 *
 * Receipt parse chain (all verified, no signature verification anywhere):
 *   zzr.onReceive (zzr.smali:167) → action check :254 → getExtras :550 →
 *   zzc.zzb/zzj build BillingResult → zzc.zzl builds [Purchase] via
 *   zzq(json, sig) → onPurchasesUpdated (:1397) → zzbq JNI bridge → C#.
 *   Purchase ctor (Purchase.smali:24) just stores the JSON; getProducts
 *   falls back to the single "productId" key (Purchase.smali:87/:39-51);
 *   getPurchaseState (:519) maps our raw 0 to 1, and
 *   Purchase$PurchaseState.smali:27 proves PURCHASED = 0x1 — so the Java
 *   getter reports PURCHASED and the raw JSON reports Play's canonical
 *   purchased=0 for any C# parser. Both interpretations are correct.
 *   Traffic Rider shipped this exact receipt shape on the same billing
 *   8.0.0 + Unity IAP stack (production-verified).
 *
 * ── 3. consumeAsync / acknowledgePurchase: answer OK locally ──────────────
 * Coin packs are consumables (C# consumes after each grant) and some items
 * need acknowledgement; against a fabricated token the real network paths
 * would fail and could flip an already-granted purchase to "failed" in C#.
 * Both methods are body-replaced at index 0 with straight-line OK replies
 * that echo the token back to the listener (Traffic Rider/Missiles pattern,
 * .registers 9 → locals v0-v5, block uses only v0/v1; no labels → safe in
 * their exception tables).
 *
 * ── 4. Engine interaction ─────────────────────────────────────────────────
 * The v3 marker-byte write (ss_iap_pending) is REMOVED: the drain thread
 * never fires, so no silent Tier-E/F double grant can race the natural
 * success flow. ss_iap_grant_engine.cpp is untouched (hooks A/B + 2 s TCS
 * backstop stay): a successful natural flow clears the TCS before the 4 s
 * backstop threshold (backstop sees Success → skips), while a stuck/pending
 * TCS still gets the Tier-E grant as fallback.
 *
 * Register budgets (verified against smali):
 * - ProxyBillingActivity.onCreate:      .registers 14 (p0=v12, p1=v13,
 *   locals v0-v11); new body uses only p0/p1.
 * - morpheFabricateSuccess (injected):  .registers 14 (p0=v13, locals
 *   v0-v12); uses v0-v11 (v10/v11 save Intent/productId across the helper).
 * - BillingClientImpl.launchBillingFlow: .registers 31 (p0=v28, p1=v29,
 *   p2=v30); injected tag reads v0 and writes only v12 (v6/v7/v11 are live
 *   longs — the ART verifier rejects touching them inside the try range).
 * - morpheTagProductId (injected):      .registers 8 (p0=v6, p1=v7, locals
 *   v0-v5); uses v0/v1.
 * - acknowledgePurchase / consumeAsync: .registers 9 (p0=v6, p1=v7,
 *   p2=v8); blocks use v0/v1.
 *
 * RUNTIME-VERIFY (device test):
 * - C# reads receipt via getOriginalJson vs getters — both forms identical.
 * - queryPurchasesAsync is NOT faked: if C# reconciles ownership against
 *   Play's query after restart, fabricated items may vanish then (hooks A/B
 *   still force tower validation for anything that IS locally owned).
 * - In-app-message / first-party ProxyBillingActivity launches (unused by
 *   this game's custom store) finish without broadcasting when the
 *   "morphe.productId" extra is absent.
 */
@Suppress("unused")
val subwaysurfersNoPlayUiPatch = bytecodePatch(
    name = "No Payment Popup",
    description = "Buy coins or items in the shop instantly — no Google Play popup, no payment, rewards arrive right away.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SUBWAY_SURFERS)

    execute {
        // ═══ 1. Injected helpers (fresh methods → labeled bodies survive) ═══

        // ProxyBillingActivity.morpheFabricateSuccess()V — build & send the
        // success broadcast, then let onCreate finish().
        val proxyBillingActivityClass = ProxyBillingActivityOnCreateFingerprint.classDef
        val morpheFabricateSuccess = ImmutableMethod(
            "Lcom/android/billingclient/api/ProxyBillingActivity;",
            "morpheFabricateSuccess",
            listOf(),
            "V",
            AccessFlags.PRIVATE.value,
            null,
            null,
            MutableMethodImplementation(14)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # v0 = launch Intent (carries our product id + the two
                # billingClient* values BillingClientImpl put at :16367/:16386)
                invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                move-result-object v0
                move-object v10, v0
                const-string v1, "morphe.productId"
                invoke-virtual {v0, v1}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1
                move-object v11, v1
                if-eqz v11, :done

                # ── classic Play receipt JSON (v11 = productId, v4:v5 = now) ──
                new-instance v2, Ljava/lang/StringBuilder;
                invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
                const-string v3, "{\"orderId\":\"GPA.morphe."
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                move-result-wide v4
                invoke-virtual {v2, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v3, "-"
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v2, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v3, "\",\"packageName\":\"com.kiloo.subwaysurf\",\"productId\":\""
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v2, v11}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                const-string v3, "\",\"purchaseState\":0,\"purchaseToken\":\"morphe-"
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v2, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v3, "\",\"purchaseTime\":"
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v2, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                const-string v3, ",\"acknowledged\":false}"
                invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v3

                # ── broadcast intent: same helper the real paths use (:347) ──
                invoke-direct {p0}, Lcom/android/billingclient/api/ProxyBillingActivity;->makePurchasesUpdatedIntent()Landroid/content/Intent;
                move-result-object v6
                const-string v7, "RESPONSE_CODE"
                const/4 v8, 0x0
                invoke-virtual {v6, v7, v8}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;
                const-string v7, "INAPP_PURCHASE_DATA"
                invoke-virtual {v6, v7, v3}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                const-string v7, "INAPP_DATA_SIGNATURE"
                const-string v8, "morphe"
                invoke-virtual {v6, v7, v8}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                const-string v7, "INTENT_SOURCE"
                const-string v8, "LAUNCH_BILLING_FLOW"
                invoke-virtual {v6, v7, v8}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                const-string v7, "billingClientTransactionId"
                const-wide/16 v8, 0x0
                invoke-virtual {v10, v7, v8, v9}, Landroid/content/Intent;->getLongExtra(Ljava/lang/String;J)J
                move-result-wide v4
                invoke-virtual {v6, v7, v4, v5}, Landroid/content/Intent;->putExtra(Ljava/lang/String;J)Landroid/content/Intent;
                const-string v7, "wasServiceAutoReconnected"
                const/4 v8, 0x0
                invoke-virtual {v10, v7, v8}, Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z
                move-result v8
                invoke-virtual {v6, v7, v8}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Z)Landroid/content/Intent;
                invoke-virtual {p0, v6}, Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V

                :done
                return-void
            """.trimIndent())
        }
        proxyBillingActivityClass.methods.add(morpheFabricateSuccess)

        // BillingClientImpl.morpheTagProductId(Intent, BillingFlowParams)V —
        // extract the tapped product id (modern zzk / legacy zzj) and stamp
        // it onto the ProxyBillingActivity launch intent.
        val billingClientImplClass = BillingClientImplLaunchBillingFlowFingerprint.classDef
        val morpheTagProductId = ImmutableMethod(
            "Lcom/android/billingclient/api/BillingClientImpl;",
            "morpheTagProductId",
            listOf(
                ImmutableMethodParameter("Landroid/content/Intent;", null, null),
                ImmutableMethodParameter("Lcom/android/billingclient/api/BillingFlowParams;", null, null)
            ),
            "V",
            AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(8)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # modern path: List<ProductDetailsParams> (never null —
                # Builder.build() falls back to empty zzbt.zzk(), :342)
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
                move-result-object v0
                if-eqz v0, :legacy
                invoke-interface {v0}, Ljava/util/List;->isEmpty()Z
                move-result v1
                if-nez v1, :legacy
                const/4 v1, 0x0
                invoke-interface {v0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, $PRODUCT_DETAILS_PARAMS
                invoke-virtual {v0}, $PRODUCT_DETAILS_PARAMS->zza()Lcom/android/billingclient/api/ProductDetails;
                move-result-object v0
                invoke-virtual {v0}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
                move-result-object v0
                goto :tag

                # legacy path: ArrayList<SkuDetails> (zzj — always non-null)
                :legacy
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v0
                invoke-virtual {v0}, Ljava/util/ArrayList;->isEmpty()Z
                move-result v1
                if-nez v1, :done
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/android/billingclient/api/SkuDetails;
                invoke-virtual {v0}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                move-result-object v0

                :tag
                const-string v1, "morphe.productId"
                invoke-virtual {p0, v1, v0}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

                :done
                return-void
            """.trimIndent())
        }
        billingClientImplClass.methods.add(morpheTagProductId)

        // ═══ 2. onCreate — fabricate success, never show Play UI ═══════════
        ProxyBillingActivityOnCreateFingerprint.method.addInstructions(0, """
            invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
            invoke-direct {p0}, Lcom/android/billingclient/api/ProxyBillingActivity;->morpheFabricateSuccess()V
            invoke-virtual {p0}, Landroid/app/Activity;->finish()V
            return-void
        """.trimIndent())

        // ═══ 3. launchBillingFlow — tag productId right before startActivity ═
        // Index of the single Activity.startActivity invoke in the method
        // (the class has exactly one, at smali :16391). The 2-instruction
        // block lands between :try_start_63b and :try_end_641, is straight-
        // line (exception-table label-drop safe) and only touches v12 — a
        // register never read after this point (v6/v7/v11 are live longs;
        // see KDoc for the ART verifier liveness proof).
        val launchBillingFlow = BillingClientImplLaunchBillingFlowFingerprint.method
        val startActivityIndex = launchBillingFlow.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_VIRTUAL &&
                getReference<MethodReference>()?.name == "startActivity"
        }
        launchBillingFlow.addInstructions(startActivityIndex, """
            move-object/from16 v12, p2
            invoke-static {v0, v12}, Lcom/android/billingclient/api/BillingClientImpl;->morpheTagProductId(Landroid/content/Intent;Lcom/android/billingclient/api/BillingFlowParams;)V
        """.trimIndent())

        // ═══ 4. acknowledgePurchase — OK to the listener, no network ═══════
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

        // ═══ 5. consumeAsync — OK + echoed token to the listener ═══════════
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
    }
}

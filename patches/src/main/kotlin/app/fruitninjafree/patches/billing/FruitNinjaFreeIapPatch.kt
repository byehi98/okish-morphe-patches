package app.fruitninjafree.patches.billing

import app.fruitninjafree.patches.il2cpp.ProcessFakeIapRedirect
import app.fruitninjafree.patches.shared.Constants.COMPATIBILITY_FRUIT_NINJA_FREE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors — the \$ escapes keep Kotlin string interpolation from
// treating "$Builder" / "$ProductDetailsParams" as template expressions. The
// trailing ';' is part of each descriptor (dropping it leaves an unterminated
// type that makes the inline smali parser fail several lines downstream).
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val BFP_PRODUCT_DETAILS_PARAMS = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams;"
private const val PURCHASE_TYPE = "Lcom/android/billingclient/api/Purchase;"
private const val BILLING_CLIENT_IMPL = "Lcom/android/billingclient/api/BillingClientImpl;"
private const val ZZAB = "Lcom/android/billingclient/api/zzab;"
private const val ZZCB = "Lcom/google/android/gms/internal/play_billing/zzcb;"

/**
 * Fruit Ninja Free 3.97.9 (versionCode 88485200, Unity 2022.3.62f2 IL2CPP,
 * Google Play Billing 8.3.0) — **Fruit Ninja Free IAP**.
 *
 * A single entry that makes the store free end-to-end: tap any item (remove-ads,
 * coins, any SKU) and it is granted instantly through the game's own backend —
 * no Google Play payment sheet ever opens, nothing is charged. It replaces three
 * earlier rounds of evidence-driven attempts that did NOT work on the device:
 * * `native-freeiap-round1.md` — Target 1/2/3 native edits: dead code or the
 *   wrong path on 3.97.9 (the "free" grant that bypassed validation also
 *   bypassed the transaction record → the store re-prompted forever).
 * * The three-anchor native-only patch this file supersedes (P1/P3 + Target 2 +
 *   Target 3): removed the signature check but still let real billing run, so a
 *   tap with no Play account / no valid token simply failed with "signature
 *   invalid".
 * * `native-freeiap-round2.md` §4.3 — a native-only P2 redirect: correct in
 *   principle, but P2 only fires once a `Purchase` has *already* been delivered,
 *   and without a Java-side forge nothing ever delivered one.
 *
 * The working design is TWO cooperating halves, both present in this APK and
 * both smali/byte-verified this session — ONE patch entry wires both together
 * (precedent: CrossyRoad `CrossyRoadFreeStorePatch`, Dead Trigger
 * `FreeStorePatch`/`NativeIapVerifierBypass`):
 *
 * ══ PART 1 — DEX: forge the purchase at the billing SDK's public entry ══════
 * [LaunchBillingFlowFingerprint] hooks `BillingClientImpl.launchBillingFlow`
 * (the SDK's own public entry — never obfuscated). The hooked body is replaced
 * by a straight-line delegate into an injected `morpheFakePurchase` helper that
 * reads the tapped SKU from `BillingFlowParams`, forges a fake `Purchase` with
 * a **unique token every tap**, and delivers it synchronously to the game's
 * registered `PurchasesUpdatedListener`
 * (`this.zzf:Lcom/android/billingclient/api/zzab;` → `zzab.zzf()` →
 * `onPurchasesUpdated`, the exact getter the original `launchBillingFlow` body
 * reads at line 10035). Unity IAP receives the purchase on the same callback it
 * uses for real Play purchases and hands it to the game.
 *
 * WHY the helper-method pattern (proven BurritoBison / Missiles regression):
 * `launchBillingFlow` carries a LARGE try/catch exception table; Morphe's
 * instruction-tree writer DROPS labeled blocks when merging a labeled
 * injection into a catch-tabled method. A freshly injected helper has no
 * exception table, so the labeled SKU-read + fake-Purchase body merges intact,
 * and `launchBillingFlow`'s own injection is a label-free straight-line
 * delegate that is catch-safe. Because `Purchase.<init>` throws
 * `JSONException` (unchecked `Exception`, not `RuntimeException`), the injected
 * `morpheFakePurchase` gets an explicit `try/catch org/json/JSONException` added
 * via [MutableMethodImplementation.addCatch] (scanned from the freshly-built
 * body — see [injectFakePurchaseHelper]); the handler builds an internal-error
 * `BillingResult` instead of letting the tap throw.
 *
 * The `zzcw` testing wrapper (`zzcw.smali:1130`) routes its own
 * `launchBillingFlow` through `invoke-super` into `BillingClientImpl`, so this
 * ONE hook covers both the direct and the wrapper path (verified).
 *
 * ══ PART 2 — NATIVE: route the forged purchase into the game's grant path ══
 * [ProcessFakeIapRedirect] (a `rawResourcePatch` dependency, never separately
 * selectable) edits `lib/<abi>/libil2cpp.so` (arm64-v8a AND armeabi-v7a, each
 * from its own measured byte site) so the game's own
 * `PurchaseManager.ProcessGooglePlayIAP` (the method Unity IAP calls for every
 * GooglePlay purchase, forged or real) tail-calls
 * `PurchaseManager.ProcessFakeIAP` — the game's BUILT-IN, still-live fake-IAP
 * grant path — instead of `ValidateAndProcessIAP`'s PlayFab
 * `ValidateGooglePlayPurchase` **server-side** receipt check. That check is
 * exactly what rejected every synthetic purchase in rounds 1–2
 * (`iap-validation-debug.md`): it verifies the Google `purchaseToken` against
 * the Play Developer API on Halfbrick's own servers, and no Java-side change
 * can pass it. `ProcessFakeIAP` posts the game's own `GrantItem` request to the
 * backend and funnels the result into the same
 * `ProcessPurchasedItems → FinalizePurchase` chain a validated purchase ends
 * in — so the forged purchase from PART 1 is granted by the game itself.
 *
 * The halves are mutually required and touch disjoint artifacts (classes*.dex vs
 * libil2cpp.so); neither works alone:
 * * PART 1 without PART 2 → Unity delivers the fake purchase, the game tries to
 *   validate it server-side, PlayFab rejects the fake token → "signature invalid".
 * * PART 2 without PART 1 → nothing ever delivers a purchase, so
 *   `ProcessGooglePlayIAP` (and the redirect) never fire → tap does nothing.
 *
 * `dependsOn(...)` runs the halves in dependency order (native first, then DEX)
 * inside a single `RawResourcePatch`-containing graph, which also forces
 * `ResourceMode.RAW_ONLY` — that is what makes the raw-extracted
 * `lib/<abi>/libil2cpp.so` (per targeted ABI) resolvable from the native
 * half's `ResourcePatchContext.get(...)`.
 *
 * ══ WHAT ONE TAP DOES, end to end (the device test this patch must pass) ═══
 * 1. Game/store → `GenericBiller.PurchaseProduct` →
 *    `BillingClientImpl.launchBillingFlow` [hooked].
 * 2. Delegate → `morpheFakePurchase(params)`: reads the tapped SKU from
 *    `params` with the original body's own DUAL path — `params.zzk()`
 *    (ProductDetails list) → `ProductDetailsParams.zza()` →
 *    `ProductDetails.getProductId()`, falling back to the legacy
 *    `params.zzj()` → first `SkuDetails` → `getSku()` when the
 *    ProductDetails wrapper or productId is null/empty (this game populates
 *    only the legacy SkuDetails list); "unknown_sku" is the last resort;
 *    forges `Purchase` JSON (unique `morphe-<sku>-<uuid>` token) and
 *    `Purchase(json, "")`; builds an OK `BillingResult`; delivers both to
 *    `onPurchasesUpdated` (the SDK's own getter path, null-guarded so a
 *    pre-connect call returns OK rather than NPE-ing); returns OK.
 * 3. Unity IAP `IStoreListener.OnPurchase` → `PurchaseManager.ProcessPurchase`
 *    → `ProcessGooglePlayIAP` **[redirected by PART 2]**
 *    → `ProcessFakeIAP` → backend `GrantItem`
 *    → `ProcessPurchasedItems → FinalizePurchase` → item granted.
 * 4. No Play sheet (step 2 returns before real billing), no server reject (step
 *    3 skips validation), no charge.
 *
 * ══ DEVICE-TEST RISKS & FALLBACKS (ordered — Waydroid decides) ══════════════
 * The token is unique per tap so Unity IAP's `HasRecordedTransaction` /
 * `ProcessPurchaseIfNew` dedup does not swallow the 2nd+ tap (a constant token
 * makes the 2nd tap hang). `source` (the redirect's `x2`) is `null` and
 * `ProcessFakeIAP`'s server-side acceptance of a client-synthesized `GrantItem`
 * are UNTESTED until the device run:
 * * **RISK 1 — server refuses `GrantItem`.** If Halfbrick's backend gates the
 *   fake-IAP endpoint in production, the tap ends in the game's normal IAP-error
 *   UI — no crash, no grant. Fallback: `native-freeiap-round2.md` §3 P3 debug
 *   menu (`morpheFreeStore`) — manual self-serve grants through the game's own
 *   UI, byte-verified, needs the same native redirect.
 * * **RISK 2 — `source = null` in the request's analytics field.** If the
 *   server validates it, the grant fails the same non-crashing way.
 * * **RISK 3 — no `queryProductDetailsAsync` / `acknowledgePurchase` /
 *   `consumeAsync` hooks.** A store that gates the buy button on a populated
 *   product catalog, or that waits on an acknowledge/consume callback, may not
 *   offer the item or may leave it pending. This round wires the purchase
 *   GRANT only; add the sibling hooks (CrossyRoad has all four) if the device
 *   run shows the store blocking the tap before `launchBillingFlow`.
 *
 * Register budgets (smali-verified):
 *  - `launchBillingFlow`  .registers 34 → p0=v31, p1=v32, p2=v33. Delegate
 *    marshals p0/p2 into v0/v1 via explicit `/from16` (`{v31,v33}` is not
 *    one encodable invoke: 35c caps at v15, 3rc needs a contiguous range), then
 *    `invoke-direct {v0, v1}` + `return-object v0`; the original catch-tabled
 *    body stays below as dead code.
 *  - `morpheFakePurchase` INJECTED `.registers 9` → p0=this=v7,
 *    p1=BillingFlowParams=v8, locals v0-v6 — every invoke ≤ v15.
 *
 * zz* names (`zzf`, `zzab.zzf`, `zzk`, `zzj`, `zza`) are Google's own
 * R8-minified names in the published billing-8.3.0 AAR — stable for this APK
 * version (compatibility is locked to 3.97.9/88485200); re-derive if the
 * billing lib version ever changes. `SkuDetails.getSku` is public API (not
 * obfuscated). `Purchase` ctor arg 2 is the signature — `""` is
 * correct (Unity IAP does not verify Google signatures client-side on
 * the GooglePlay path; the real check is the server one PART 2 skips).
 */
@Suppress("unused")
val fruitNinjaFreeIapPatch = bytecodePatch(
    name = "Free IAP",
    description = "Everything in the shop is free. Tap \"Buy\" on any item — remove ads, coin packs, or anything else — and you get it right away. No Google Play payment window opens and you are never charged. If something goes wrong you will just see a \"purchase failed\" message; the game keeps working as normal.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FRUIT_NINJA_FREE)

    // ══ PART 2 — native ProcessGooglePlayIAP → ProcessFakeIAP redirect ══════
    // Defined via the ProcessFakeIapRedirect builder (NOT a top-level val/fun)
    // so PatchLoader never discovers it as a second selectable entry — it is a
    // dependency of THIS patch and runs when this patch is selected.
    dependsOn(ProcessFakeIapRedirect.patch())

    execute {
        // ══ PART 1 — launchBillingFlow forge ═══════════════════════════════
        // 1. Inject the helper (no exception table) that actually forges + delivers.
        // 2. Add the JSONException catch by scanning the freshly-built body.
        // 3. Prepend a straight-line delegate to the original (catch-tabled)
        //    launchBillingFlow.
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
            "Lcom/android/billingclient/api/BillingResult;",
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(9),
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # ── 1. OK BillingResult (persisted in v0, live throughout) ──
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v1, v2}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v1
                invoke-virtual {v1}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v0

                # ── 2. SKU from p1 (BillingFlowParams) — mirrors the original
                #      launchBillingFlow body's own DUAL path (BillingClientImpl.smali
                #      10123-10177): try zzk() → ProductDetailsParams →
                #      ProductDetails.getProductId() first; when the wrapper or the
                #      productId is null/empty, fall back to the legacy zzj() → first
                #      SkuDetails → getSku() (this game populates only the legacy
                #      SkuDetails list — BillingFlowParams.zzf, field zze is an empty
                #      zzbw). The first-element reads use the SDK's own
                #      zzcb.zza(Iterable, null) — the exact helper launchBillingFlow
                #      uses at 10135/10144 — but that helper does NOT null-guard the
                #      iterable itself (NPE on a null list), hence the if-eqz list
                #      guards. Null/empty at any step falls through to the next path;
                #      "unknown_sku" stays the last resort.
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzk()Ljava/util/List;
                move-result-object v1
                if-eqz v1, :fnf_sku_legacy
                invoke-static {v1, v2}, $ZZCB->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, $BFP_PRODUCT_DETAILS_PARAMS
                if-eqz v1, :fnf_sku_legacy
                invoke-virtual {v1}, $BFP_PRODUCT_DETAILS_PARAMS->zza()Lcom/android/billingclient/api/ProductDetails;
                move-result-object v4
                if-eqz v4, :fnf_sku_legacy
                invoke-virtual {v4}, Lcom/android/billingclient/api/ProductDetails;->getProductId()Ljava/lang/String;
                move-result-object v4
                if-eqz v4, :fnf_sku_legacy
                invoke-virtual {v4}, Ljava/lang/String;->isEmpty()Z
                move-result v1
                if-eqz v1, :fnf_sku_ok
                :fnf_sku_legacy
                invoke-virtual {p1}, Lcom/android/billingclient/api/BillingFlowParams;->zzj()Ljava/util/ArrayList;
                move-result-object v1
                if-eqz v1, :fnf_no_sku
                invoke-static {v1, v2}, $ZZCB->zza(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lcom/android/billingclient/api/SkuDetails;
                if-eqz v1, :fnf_no_sku
                invoke-virtual {v1}, Lcom/android/billingclient/api/SkuDetails;->getSku()Ljava/lang/String;
                move-result-object v4
                if-nez v4, :fnf_sku_ok
                :fnf_no_sku
                const-string v4, "unknown_sku"
                :fnf_sku_ok

                # ── 3. Unique-per-tap UUID (v2) so Unity IAP's transaction dedup
                #      (HasRecordedTransaction / ProcessPurchaseIfNew) never swallows
                #      the 2nd+ tap; a constant token makes repeat taps hang. ──
                invoke-static {}, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;
                move-result-object v1
                invoke-virtual {v1}, Ljava/util/UUID;->toString()Ljava/lang/String;
                move-result-object v2

                # ── 4. Fake Purchase JSON — orderId + purchaseToken embed the unique
                #      UUID; packageName matches Constants.PACKAGE_NAME; productIds is
                #      the array BillingClientImpl's own zza() reads (fallback
                #      productId); purchaseState 0 → PURCHASED; acknowledged true so
                #      Unity IAP skips its acknowledge IPC on the fake token. ──
                new-instance v1, Ljava/lang/StringBuilder;
                invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
                const-string v6, "{\"orderId\":\"GPA.morphe-"
                invoke-virtual {v1, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v6, "\",\"packageName\":\"com.halfbrick.fruitninjafree\",\"productId\":\""
                invoke-virtual {v1, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v6, "\",\"productIds\":[\""
                invoke-virtual {v1, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v6, "\"],\"purchaseTime\":0,\"purchaseState\":0,\"purchaseToken\":\"morphe-"
                invoke-virtual {v1, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v6, "-"
                invoke-virtual {v1, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                const-string v6, "\",\"quantity\":1,\"acknowledged\":true,\"autoRenewing\":false,\"signature\":\"\"}"
                invoke-virtual {v1, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v1
                invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v5

                # ── 5. new Purchase(json, "") — arg2 is the signature ("" = no
                #      client-side verify; the real check is the server one PART 2
                #      skips). May throw JSONException — caught by the catch added
                #      below, handler :fnf_json. ──
                const-string v2, ""
                new-instance v3, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v3, v5, v2}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V

                # ── 6. Registered listener: this.zzf → zzab.zzf(), null-guarded so a
                #      pre-connect call returns OK instead of NPE-ing (mirrors the
                #      original body's guard at line 10028). ──
                iget-object v1, p0, $BILLING_CLIENT_IMPL->zzf:$ZZAB
                if-eqz v1, :fnf_done
                invoke-virtual {v1}, $ZZAB->zzf()Lcom/android/billingclient/api/PurchasesUpdatedListener;
                move-result-object v1
                if-eqz v1, :fnf_done
                invoke-static {v3}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v2
                invoke-interface {v1, v0, v2}, Lcom/android/billingclient/api/PurchasesUpdatedListener;->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V

                :fnf_done
                return-object v0

                # ── JSONException catch (handler). build() never throws here (v1 is a
                #      fresh Builder, v2 is the OK response code), so the handler body
                #      cannot itself re-throw. move-exception is mandatory: a DEX catch
                #      handler must begin with it, and injectFakePurchaseHelper's scan
                #      uses it to locate the handler index for addCatch. ──
                :fnf_json
                move-exception v3
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v1
                const/4 v2, 0x5
                invoke-virtual {v1, v2}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v1
                invoke-virtual {v1}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v0
                return-object v0
                nop
            """.trimIndent())
        }
        val helperImpl = morpheFakePurchase.implementation
            ?: throw PatchException(
                "Fruit Ninja Free IAP: the injected morpheFakePurchase helper has no " +
                    "implementation — cannot attach the JSONException catch. This is " +
                    "unexpected; re-derive the helper body before shipping.",
            )
        injectFakePurchaseHelper(helperImpl)
        billingClientImplClass.methods.add(morpheFakePurchase)

        // 3. Straight-line delegate at the original entry — no labels/branches, so
        //    the catch-tabled original body below it is untouched and never reached.
        LaunchBillingFlowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-direct {v0, v1}, $BILLING_CLIENT_IMPL->morpheFakePurchase(Lcom/android/billingclient/api/BillingFlowParams;)Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent())
    }
}

/**
 * Adds an explicit `org/json/JSONException` catch to the freshly-built
 * `morpheFakePurchase` helper, with boundaries **scanned from the body itself**
 * rather than hard-coded to instruction indices:
 * * `tryStart` = the `new-instance Purchase` (`NEW_INSTANCE` whose reference is
 *   `Purchase`) — everything before it is JSON-free, so the range is as tight
 *   as possible.
 * * `tryEnd`   = the `invoke-direct Purchase.<init>` (`INVOKE_DIRECT` whose
 *   `MethodReference` is `Purchase.<init>`) + 1 (dex try ranges are end-
 *   exclusive).
 * * `handler`  = the first `MOVE_EXCEPTION` at/after `tryStart` (the smali
 *   `:fnf_json` label).
 *
 * If the layout is not what the body above declares, this throws [PatchException]
 * instead of silently attaching a catch to the wrong range.
 */
private fun injectFakePurchaseHelper(helperImpl: MutableMethodImplementation) {
    var tryStart = -1
    var tryEnd = -1
    var handler = -1
    for ((index, instruction) in helperImpl.instructions.withIndex()) {
        // MOVE_EXCEPTION carries no reference, so it must be matched BEFORE the
        // ReferenceInstruction guard below — that guard `continue`s past every
        // non-reference instruction, which would silently skip the handler.
        if (instruction.opcode == Opcode.MOVE_EXCEPTION &&
            tryStart >= 0 &&
            handler < 0
        ) {
            handler = index
            continue
        }
        val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
        when {
            instruction.opcode == Opcode.NEW_INSTANCE &&
                (reference as? TypeReference)?.type == PURCHASE_TYPE &&
                tryStart < 0 ->
                tryStart = index

            instruction.opcode == Opcode.INVOKE_DIRECT &&
                reference is MethodReference &&
                reference.definingClass == PURCHASE_TYPE &&
                reference.name == "<init>" ->
                tryEnd = index + 1 // dex try ranges are end-exclusive.
        }
    }
    if (tryStart < 0 || tryEnd <= tryStart || handler < tryEnd) {
        throw PatchException(
            "Fruit Ninja Free IAP: could not locate the Purchase-cast / <init> / " +
                "JSONException-handler boundaries in the injected morpheFakePurchase " +
                "helper (tryStart=$tryStart tryEnd=$tryEnd handler=$handler) — " +
                "the helper body changed; re-derive them before attaching a catch.",
        )
    }
    helperImpl.addCatch(
        "Lorg/json/JSONException;",
        helperImpl.newLabelForIndex(tryStart),
        helperImpl.newLabelForIndex(tryEnd),
        helperImpl.newLabelForIndex(handler),
    )
}

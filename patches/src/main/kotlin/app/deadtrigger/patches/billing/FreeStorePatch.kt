package app.deadtrigger.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import app.deadtrigger.patches.il2cpp.NativeIapVerifierBypass
import app.deadtrigger.patches.shared.Constants.COMPATIBILITY_DEAD_TRIGGER

/**
 * Dead Trigger 2.3.4 — Free Store / Free IAP (scope: IAP only; ads are a separate patch).
 *
 * ============================================================================
 * WHY THIS LIVES ENTIRELY IN JAVA
 * ============================================================================
 * Dead Trigger is Unity IL2CPP. The purchase *decision* is native C#
 * (`Madfinger.Plugins.Billing.GooglePlay.*` → `IAPRequestPurchase` → `IAPProcessBoughtItem`),
 * but it talks to the Play Billing library through a thin, JNI-bound Java bridge:
 *
 *   C#  AndroidJavaClass("com.madfingergames.billing.BillingManager")
 *       requestPurchase(sku, type, UnityCallback proxy)
 *         -> startCommand -> connect -> queryProductDetailsAsync
 *         -> BillingClient.launchBillingFlow   <-- the ONLY real payment UI in the app
 *         -> UnityCallback.onResult(code, Purchase)   <-- crosses back into C#
 *
 * `com.madfingergames.billing.*` names are hardcoded C# strings, so they are JNI-bound and
 * cannot be obfuscated — the fingerprints for them are stable across updates.
 *
 * Java performs **no receipt validation** (no HTTP in `com.madfingergames.*`, no
 * `Security.verify`/SHA1withRSA outside Google LVL/SDK internals), so a fabricated `Purchase`
 * handed to `UnityCallback.onResult` is delivered to C# exactly like a real one.
 *
 * ============================================================================
 * TARGETS PATCHED (all together — they are mutually required)
 * ============================================================================
 *  T1  requestPurchase(String,String,UnityCallback)V          (.registers 4, STATIC)
 *      PRIMARY. Body replaced: build a fabricated `Purchase` around the REAL tapped sku (p0)
 *      and deliver `p2.onResult(0, purchase)` directly, then return-void. Play is never
 *      launched, nothing is charged. `Purchase.<init>(String,String)V` is public and only
 *      stores the JSON + parses a `JSONObject` (classes5/Purchase.smali:24) — no signature
 *      check — but the JSON MUST carry a `"productIds"` array, which `Purchase.zza()` reads
 *      via `getProducts()`/`getSkus()`. Register budget: 1 local + 3 params → expanded with
 *      cloneMutable(additionalRegisters = 3) → locals v0-v3, p0-p2 at the top.
 *
 *  T1b BillingManager$4$1.onProductDetailsResponse(BillingResult,List)V  (.registers 6)
 *      ASYNC FALLBACK / CHOKE. This listener holds the app's only `launchBillingFlow` call
 *      site (verified with rg over smali/), so replacing its body means real payment UI can
 *      never open even along an unanticipated path. With T1 active it is never instantiated
 *      (T1 no longer constructs `BillingManager$4`), so it is defensive code rather than a
 *      second delivery — it is kept because it is also the ready-made *async* deliverer (it
 *      runs on the UI thread) if the synchronous T1 callback ever turns out to misbehave from
 *      inside the JNI frame. In that scenario T1's body patch would be reverted to keep the
 *      original `$4` handoff and this target becomes the active one. No register expansion
 *      needed (locals v0-v2 are exactly what the injected block uses).
 *
 *  T2  consumePurchase(String,UnityCallback)V                    (.registers 3, STATIC)
 *      REQUIRED COMPANION. C# consumes consumables after a grant; `consumeAsync` rejects the
 *      fabricated token with Play error 5 and the grant is dropped (proven on device for
 *      Into the Dead 2). Body replaced with `p1.onResult(0, null)` — identical to what the
 *      real `BillingManager$5$1.onConsumeResponse` forwards. Fits the existing register file
 *      (1 local + 2 params) with no expansion.
 *
 *  T3  queryPurchases(String,UnityCallback)V                     (.registers 3, STATIC)
 *      Restore/ownership query. A sideloaded build gets `[]` from Play, so the `Weekly`
 *      subscription reads as unowned. Body replaced: build a `java.util.ArrayList` holding
 *      one fabricated `Weekly` purchase, then `p1.onResult(0, list)`. The skuType param (p0)
 *      is deliberately IGNORED — returning the owned subscription for *both* the "subs" and
 *      "inapp" queries guarantees C# sees the ownership whichever way it asks (a strict
 *      type-partitioning reader will simply not match "Weekly" against the inapp catalog).
 *      Token is fixed (`morphe-weekly`) on purpose: it is a stable identity across launches.
 *      Register budget: 3 locals needed → cloneMutable(additionalRegisters = 3).
 *
 *  T4  connect(UnityCallback)V                                 (.registers 3, STATIC)
 *      Availability gate. Without a working Play Store the real `startConnection` reports an
 *      error and C# disables the store (`IAPServiceAvailable`). Body replaced with
 *      `p0.onResult(0, null)` — identical to `BillingManager$2$1.onBillingSetupFinished` on
 *      success. Fits existing registers (2 locals + 1 param), no expansion.
 *
 *  T5  querySkuDetails(String[],String,UnityCallback)V          (.registers 4, STATIC)
 *      Included beyond the "optional" label for a concrete safety reason: it is the only
 *      `startCommand` caller left unpatched otherwise, and `startCommand`'s not-ready path
 *      wraps the request in `BillingManager$7` and calls `connect($7)`. Patched `connect`
 *      answers 0 (OK), `$7.onResult` therefore takes its SUCCESS branch and runs
 *      `BillingManager$3.run()`, which dereferences the still-NULL `mBillingClient` in
 *      `querySkuDetailsAsync` (it is only assigned in `BillingManager$2.run()`, which patched
 *      `connect` never reaches) → NPE crash. Patching this entry point keeps `startCommand`
 *      permanently unreachable (its only callers are T1/T2/T3/T5, all patched) and doubles as
 *      the fake price catalog so the store UI stays usable without Play. Register budget:
 *      loop needs 6 locals → cloneMutable(additionalRegisters = 6).
 *
 *  T6  isGoogleStoreInstalled()Z                               (.registers 2, STATIC)
 *      Forced to `true` so the shop is reachable on devices without Play Store. No-op on
 *      Play-equipped devices (already true).
 *
 * Not patched: `BillingManager$1.onPurchasesUpdated` (real-flow listener — unreachable because
 * the client object is never even built), `onPurchaseError` (only reachable from T1b's original
 * body), `openGoogleStore` (explicit user action, no payment), `acknowledgePurchase` (there is
 * NO acknowledge API in this bridge at all — rg finds none), and `startCommand` itself (dead
 * once T1/T2/T3/T5 are patched).
 *
 * ============================================================================
 * NATIVE-STAGE CAVEAT — IAPVerifier (RESOLVED: inline dependency below)
 * ============================================================================
 * The C# side contains an `IAPVerifier` plus an embedded RSA public key; Java validates
 * nothing, so this patch delivers the fabricated receipt successfully — but the native
 * stage then drops it. CONFIRMED on device: `IAP.IAPVerifier.<VerifyTransaction_Platform>d__5`
 * does a purely LOCAL RSA-SHA1 check (no server, no network anywhere in the chain),
 * `Convert.FromBase64String("morphe-signature")` throws `FormatException`, the catch
 * handler callbacks `CantVerify(0)`, and `Billing.Buy_Internal d__33` maps that to
 * `EBuyState.Failure(2)` → `m_LastBuyResult != Success` → the d__79 grant gate never
 * calls `IAPProcessBoughtItem` → grant dropped (logcat: `Verify transaction done:
 * CantVerify` → `Buy end Failure`).
 *
 * The native companion that forces the verdict lives in
 * `../il2cpp/NativeIapVerifierBypass.kt` (static libil2cpp.so edit, both ABIs) and is
 * attached to THIS patch as an inline `dependsOn(rawResourcePatch{…})` dependency —
 * the CrossyRoad "ONE listing entry" shape. BytecodePatchContext has no file API
 * (`get(path)` exists only on ResourcePatchContext — javap-verified), so the two
 * halves must be two Patch objects; they are wired together as parent + dependency
 * instead of two separately selectable patches. Enabling "Dead Trigger Free Store"
 * runs BOTH halves (dependency execute runs before this file's hooks); disabling it
 * runs neither. The two halves are mutually required: this patch alone fakes the
 * purchase UI-side but grants silently die; the native half alone has no fake
 * purchase to approve. Discovery/execution details: see the STRUCTURE section of
 * `../il2cpp/NativeIapVerifierBypass.kt` (the dependency is an instance-method
 * builder on an `internal object`, so PatchLoader never lists it separately).
 *
 * The earlier "force the built-in `Madfinger.Plugins.Billing.Dummy` processor" idea is
 * INVALID — no `Dummy : IProcessor` exists in the metadata; `IAP.<Initialize_Coroutine>d__16`
 * unconditionally does `new IAPVerifier()` and `IAP.IAPVerifier` is the only IProcessor.
 * Authoritative findings: analysis/deadtrigger/notes/native-iap-verifier.md.
 *
 * Build/test/deploy is a separate stage (patch-deployer) — this file is code-only.
 */
@Suppress("unused")
val deadTriggerFreeStorePatch = bytecodePatch(
    name = "Dead Trigger Free Store",
    description = "Free store: tap any gold or money pack in the shop and it's yours instantly.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TRIGGER)

    // ══ Native IAP verifier half — inline rawResourcePatch dependency ═══════
    // Built in ../il2cpp/NativeIapVerifierBypass.kt. Its builder is a member of
    // an `internal object` (an INSTANCE method), which PatchLoader's discovery
    // (public STATIC fields/methods returning Patch) never sees — so the native
    // half is not a separate listing entry: this patch remains the ONLY
    // selectable Dead Trigger entry, and toggling it toggles both halves.
    // The dependency's execute{} runs against ResourcePatchContext (the only
    // context with get(path)) BEFORE the bytecode hooks below; the two halves
    // touch disjoint artifacts (classes*.dex vs lib/<abi>/libil2cpp.so), so their
    // relative order is irrelevant. A RawResourcePatch anywhere in the patch
    // graph forces ResourceMode.RAW_ONLY, which is what makes the lib/ extract
    // and get("lib/<abi>/libil2cpp.so", true) resolve. `default` on the
    // dependency is documentation only — the patcher never gates dependencies
    // on selection flags; it runs iff this patch runs.
    dependsOn(NativeIapVerifierBypass.patch())

    execute {
        // =====================================================================
        // T1 — requestPurchase: fake the purchase, skip Play payment (PRIMARY)
        // =====================================================================
        // .registers 4 (static): 1 local `v0` + p0=sku, p1=skuType, p2=callback.
        // The injected block needs three locals (StringBuilder / temp / Purchase),
        // so the method is cloned with +3 registers and the original swapped out
        // (locals become v0-v3, params p0-p2 move to the top; p-aliases stay valid).
        val requestPurchaseMethod = RequestPurchaseFingerprint.method
        val requestPurchaseExpanded = requestPurchaseMethod.cloneMutable(additionalRegisters = 3)
        mutableClassDefBy(requestPurchaseMethod.definingClass).methods.apply {
            remove(requestPurchaseMethod)
            add(requestPurchaseExpanded)
        }
        // Fabricated purchase JSON wraps the REAL tapped sku (p0) so C# matches the
        // bought item. `purchaseToken` is a fresh UUID on every tap so repeat purchases
        // of the same SKU can never be de-duplicated against a stored token.
        requestPurchaseExpanded.addInstructions(
            0, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.madfingergames.deadtrigger\",\"productId\":\""
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\",\"productIds\":[\""
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\"],\"purchaseState\":0,\"purchaseTime\":0,\"purchaseToken\":\"morphe-"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-static {}, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;
            move-result-object v1
            invoke-virtual {v1}, Ljava/util/UUID;->toString()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\",\"quantity\":1,\"acknowledged\":false}"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            const-string v1, "morphe-signature"
            new-instance v2, Lcom/android/billingclient/api/Purchase;
            invoke-direct {v2, v0, v1}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
            sput-object p0, Lcom/madfingergames/billing/BillingManager;->mTargetSku:Ljava/lang/String;
            sput-object p2, Lcom/madfingergames/billing/BillingManager;->mPurchaseCallback:Lcom/madfingergames/billing/UnityCallback;
            const/4 v0, 0x0
            invoke-interface {p2, v0, v2}, Lcom/madfingergames/billing/UnityCallback;->onResult(ILjava/lang/Object;)V
            return-void
        """.trimIndent()
        )

        // =====================================================================
        // T1b — onProductDetailsResponse: async fallback + payment-UI choke
        // =====================================================================
        // .registers 6 (instance): locals v0-v2, p0=this, p1=BillingResult, p2=List.
        // No expansion needed. Reads the tapped sku and the callback through the statics
        // T1/bookkeeping maintains (mTargetSku / mPurchaseCallback) — no `$`-bearing
        // field descriptors are referenced, which keeps the injected smali free of
        // Kotlin string-template hazards.
        ProductDetailsResponseFingerprint.method.addInstructions(
            0, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.madfingergames.deadtrigger\",\"productId\":\""
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            sget-object v1, Lcom/madfingergames/billing/BillingManager;->mTargetSku:Ljava/lang/String;
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\",\"productIds\":[\""
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            sget-object v1, Lcom/madfingergames/billing/BillingManager;->mTargetSku:Ljava/lang/String;
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\"],\"purchaseState\":0,\"purchaseTime\":0,\"purchaseToken\":\"morphe-"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-static {}, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;
            move-result-object v1
            invoke-virtual {v1}, Ljava/util/UUID;->toString()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, "\",\"quantity\":1,\"acknowledged\":false}"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            const-string v1, "morphe-signature"
            new-instance v2, Lcom/android/billingclient/api/Purchase;
            invoke-direct {v2, v0, v1}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
            const/4 v0, 0x0
            sget-object v1, Lcom/madfingergames/billing/BillingManager;->mPurchaseCallback:Lcom/madfingergames/billing/UnityCallback;
            invoke-interface {v1, v0, v2}, Lcom/madfingergames/billing/UnityCallback;->onResult(ILjava/lang/Object;)V
            return-void
        """.trimIndent()
        )

        // =====================================================================
        // T2 — consumePurchase: report consume OK (else Play error 5 drops the grant)
        // =====================================================================
        // .registers 3 (static): 1 local `v0` + p0=token, p1=callback. Three
        // instructions, fits as-is — no register expansion.
        ConsumePurchaseFingerprint.method.addInstructions(
            0, """
            const/4 v0, 0x0
            invoke-interface {p1, v0, v0}, Lcom/madfingergames/billing/UnityCallback;->onResult(ILjava/lang/Object;)V
            return-void
        """.trimIndent()
        )

        // =====================================================================
        // T3 — queryPurchases: fabricate the owned list (Weekly subscription)
        // =====================================================================
        // .registers 3 (static): 1 local `v0` + p0=skuType, p1=callback.
        // Needs three locals (json / signature / Purchase, then list) → +3.
        val queryPurchasesMethod = QueryPurchasesFingerprint.method
        val queryPurchasesExpanded = queryPurchasesMethod.cloneMutable(additionalRegisters = 3)
        mutableClassDefBy(queryPurchasesMethod.definingClass).methods.apply {
            remove(queryPurchasesMethod)
            add(queryPurchasesExpanded)
        }
        queryPurchasesExpanded.addInstructions(
            0, """
            const-string v0, "{\"orderId\":\"GPA.morphe\",\"packageName\":\"com.madfingergames.deadtrigger\",\"productId\":\"Weekly\",\"productIds\":[\"Weekly\"],\"purchaseState\":0,\"purchaseTime\":0,\"purchaseToken\":\"morphe-weekly\",\"quantity\":1,\"acknowledged\":false}"
            const-string v1, "morphe-signature"
            new-instance v2, Lcom/android/billingclient/api/Purchase;
            invoke-direct {v2, v0, v1}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
            invoke-virtual {v0, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            const/4 v2, 0x0
            invoke-interface {p1, v2, v0}, Lcom/madfingergames/billing/UnityCallback;->onResult(ILjava/lang/Object;)V
            return-void
        """.trimIndent()
        )

        // =====================================================================
        // T4 — connect: report "connected" without Play Store
        // =====================================================================
        // .registers 3 (static): locals v0/v1 + p0=callback. Two instructions + return,
        // fits as-is — no register expansion. Response 0 + null data is exactly what
        // BillingManager$2$1.onBillingSetupFinished forwards on success.
        ConnectFingerprint.method.addInstructions(
            0, """
            const/4 v0, 0x0
            const/4 v1, 0x0
            invoke-interface {p0, v0, v1}, Lcom/madfingergames/billing/UnityCallback;->onResult(ILjava/lang/Object;)V
            return-void
        """.trimIndent()
        )

        // =====================================================================
        // T5 — querySkuDetails: fake the price catalog (also NPE-guard, see KDoc)
        // =====================================================================
        // .registers 4 (static): 1 local `v0` + p0=skus, p1=skuType, p2=callback.
        // The loop needs six locals (list / index / length / sku / builder / temp)
        // → cloneMutable(additionalRegisters = 6) → locals v0-v6, p0-p2 on top.
        val querySkuDetailsMethod = QuerySkuDetailsFingerprint.method
        val querySkuDetailsExpanded = querySkuDetailsMethod.cloneMutable(additionalRegisters = 6)
        mutableClassDefBy(querySkuDetailsMethod.definingClass).methods.apply {
            remove(querySkuDetailsMethod)
            add(querySkuDetailsExpanded)
        }
        // One fabricated SkuDetails per requested sku. SkuDetails.<init> only requires a
        // non-empty `productId` and `type` (classes5/SkuDetails.smali:18 throws otherwise);
        // every other getter is `opt*`-based, so the remaining fields are cosmetic.
        // `type` is taken from the requested skuType (p1), `subscriptionPeriod` is always
        // present so a C#-side ISO-8601 parse of the Weekly item cannot see an empty string.
        // Price is a fixed fake — nothing is ever charged.
        querySkuDetailsExpanded.addInstructionsWithLabels(
            0, """
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
            if-eqz p0, :morphe_done_skus
            array-length v2, p0
            const/4 v1, 0x0
            :morphe_loop_skus
            if-ge v1, v2, :morphe_done_skus
            aget-object v3, p0, v1
            new-instance v4, Ljava/lang/StringBuilder;
            invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V
            const-string v5, "{\"productId\":\""
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"type\":\""
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"title\":\"Dead Trigger 2: "
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v5, "\",\"description\":\"Dead Trigger 2\",\"price\":\"${'$'}0.99\",\"price_amount_micros\":990000,\"price_currency_code\":\"USD\",\"subscriptionPeriod\":\"P1W\"}"
            invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v4
            new-instance v5, Lcom/android/billingclient/api/SkuDetails;
            invoke-direct {v5, v4}, Lcom/android/billingclient/api/SkuDetails;-><init>(Ljava/lang/String;)V
            invoke-virtual {v0, v5}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            add-int/lit8 v1, v1, 0x1
            goto :morphe_loop_skus
            :morphe_done_skus
            const/4 v5, 0x0
            invoke-interface {p2, v5, v0}, Lcom/madfingergames/billing/UnityCallback;->onResult(ILjava/lang/Object;)V
            return-void
        """.trimIndent()
        )

        // =====================================================================
        // T6 — isGoogleStoreInstalled: always true (store gate opens without Play)
        // =====================================================================
        // .registers 2, no parameters → returnEarly(true) emits `const/4 v0, 0x1` +
        // `return v0`, both valid in the existing register file.
        IsGoogleStoreInstalledFingerprint.method.returnEarly(true)
    }
}

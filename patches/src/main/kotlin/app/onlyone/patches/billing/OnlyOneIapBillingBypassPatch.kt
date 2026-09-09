package app.onlyone.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.onlyone.patches.misc.SecurityVerifyPurchaseFingerprint
import app.onlyone.patches.shared.Constants.COMPATIBILITY_ONLY_ONE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow

// Class/field descriptors (verified against classes4/.../LuaLoader.smali).
// The \$ escapes keep Kotlin string interpolation from treating
// "$Builder" as a template expression. NOTE: the trailing ';' is part of
// each descriptor — dropping it leaves an unterminated type that makes
// InlineSmaliCompiler report cascade lexer errors downstream
// (see BurritoBisonFreePurchasePatch).
private const val LUA_LOADER = "Lplugin/google/iap/billing/v2/LuaLoader;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"

// Receipt signature field for validateReceipt (IAPGoogle.lu main.0.4) — it only
// checks string.len(signature) > 10, content is irrelevant because
// Security.verifyPurchase is pinned in step 1 of OnlyOneFreeStorePatch AND in
// step 1 below (self-contained when installed without the free-store patch).
private const val FORGED_SIGNATURE = "MORPHESIGNATUREMORPHESIGNATURE"

/**
 * Only One — IAP billing bypass (free purchases at the Buy button).
 *
 * Complements OnlyOneFreeStorePatch (which fabricates the whole catalog via
 * "Restore Purchases"). This patch intercepts the ACTIVE buy flow so tapping
 * any Buy button grants that item instantly without the Google Play payment
 * sheet — one item per tap with the real productIdentifier and correct
 * "type" field, exactly as the game's own purchase semantics expect.
 *
 * Buy flow of the stock plugin (all names intact because
 * require("plugin.google.iap.billing.v2") loads it from Lua):
 *
 *   Lua purchase() / purchaseSubscription()
 *     → purchaseType(LuaState, QueryPurchasesParams)         [line 1297]
 *         ├─ cached fCachedProductDetails[productId] exists:
 *         │    launchBillingFlow(activity, params)           [line 1959]
 *         └─ not cached: LuaLoader$8.onProductDetailsResponse
 *              (fetches details, caches them, then
 *               launchBillingFlow(activity, params)          [$8 line 292])
 *     → PurchasesUpdatedListener == LuaLoader.onPurchasesUpdated [line 2251]
 *         → Security.verifyPurchase per row → StoreTransactionRuntimeTask
 *           (state "purchased" when responseCode==0) → Lua storeTransaction
 *           event → IAPGoogle.lu validateReceipt → grant + save()
 *
 * Patch design:
 *   1. onPurchasesUpdated is NOT pinned beyond Security.verifyPurchase:
 *      morpheFakePurchase delivers a genuinely-built OK BillingResult, so the
 *      StoreTransactionRuntimeTask ctor reads responseCode==0 → state
 *      "purchased" naturally. (A `const/4 p1, 0x0` pin here was a shipped bug:
 *      p1 IS the BillingResult parameter — nulling it NPEs inside the task
 *      ctor: "int BillingResult.getResponseCode()' on a null object
 *      reference" on every Buy tap. Never "pin" a return value by writing a
 *      const into the parameter register itself.)
 *   2. Security.verifyPurchase pinned true (self-containment when installed
 *      without OnlyOneFreeStorePatch; harmless duplicate insert when both
 *      run — second insert sits above the first).
 *   3. A private helper `morpheFakePurchase(String sku)` is injected INTO
 *      LuaLoader. It builds an OK BillingResult, forges a Purchase for the
 *      requested SKU (unique orderId/token per tap via nanoTime), wraps it in
 *      a singleton list and calls this.onPurchasesUpdated(...) — the exact
 *      sink the real billing pipeline uses, so CoronaRuntimeTaskDispatcher
 *      threading, the Lua event table and validateReceipt (receipt contains
 *      productId; signature length > 10) all run unchanged.
 *   4. Both launchBillingFlow paths are hooked, straight-line only. The
 *      purchaseType guard is TWO-SITE because the method has two merge paths
 *      into the cached-details lookup (verified in patched output): the
 *      non-table path falls through into the fCachedProductDetails sget, but
 *      the options-table path (accountId/profileId parsing) ends with an
 *      UNCONDITIONAL `goto` straight to the sget — a plain insertion before
 *      the sget is jumped over by it. So:
 *        - Guard A sits immediately before the sget (covers fall-through),
 *        - Guard B sits immediately before that unconditional goto (covers
 *          the table path; the goto becomes dead code).
 *        - LuaLoader$8.onProductDetailsResponse stays hooked as
 *          belt-and-braces for any residual second purchaseType call.
 *
 * WHY a helper instead of inline labeled smali at the hooks: purchaseType
 * contains try/catch regions (accountId/profileId hashing, try_start_4b,
 * try_start_8a). The instruction-tree writer can drop labeled blocks when
 * merging labeled injections into methods with exception tables (documented
 * in BurritoBisonFreePurchasePatch), so both hook points stay straight-line
 * and the labeled body lives in the catch-free helper.
 *
 * Register accounting (all verified against the smali):
 *   purchaseType (.registers 20, this=p0→v2 alias, LuaState=p1→v1 alias):
 *     both guard sites sit after the initSuccessful/productId reads and before
 *     the fCachedProductDetails sget. At both points v2 = this (aliased at the
 *     head, never rewritten before the sget), v5 = productId (assigned once at
 *     smali line 1339, never rewritten), v7 = 0 (the "nothing pushed" return
 *     value every early exit and the tail use). Guard = invoke-direct {v2, v5}
 *     + return v7.
 *   LuaLoader$8.onProductDetailsResponse (.registers 6): p2 is dead at entry
 *     (first write: p2 = p1.iterator() after p1 is overwritten), p1 only feeds
 *     the unreachable error branches. Guard = invoke-direct {p0, p2} + return.
 *   morpheFakePurchase (.registers 7 → locals v0-v4, p0=v5 this, p1=v6 sku):
 *     v0 = OK BillingResult (live), v1/v2 = WIDE timestamp pair — never written
 *     between move-result-wide and append(J) (a const-string into v2 while the
 *     pair was live produced a runtime VerifyError: "wide register v1 has type
 *     Long (Low Half)/Reference: java.lang.String" — the verifier rejects the
 *     whole LuaLoader class and the plugin fails to load), v3 = builder/JSON/
 *     Purchase, v4 = dedicated string scratch. All registers ≤ v15 → every
 *     35c invoke encodable.
 */
@Suppress("unused")
val onlyOneIapBillingBypassPatch = bytecodePatch(
    name = "Only One IAP billing bypass",
    description = "Buy any item in the shop for free. When you tap Buy, the item " +
        "is added to your game right away — no Google Play payment window ever opens.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ONLY_ONE)

    execute {
        // ── 1. Open the signature gate ─────────────────────────────────
        // Security.verifyPurchase: always true, so forged rows (and rows
        // delivered by any other path) dispatch as storeTransaction events.
        // Duplicates OnlyOneFreeStorePatch step 1 on purpose — harmless
        // when both patches run (second insert sits above the first).
        SecurityVerifyPurchaseFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // ── 2. Inject the forged-purchase helper into LuaLoader ────────
        // .registers 7 → locals v0-v4, p0=v5 (this), p1=v6 (sku).
        // WIDE-REGISTER RULE: v1/v2 hold the timestamp long; NOTHING may
        // write v1 or v2 between move-result-wide and its append(J) use —
        // reuse of the high half as a string scratch caused a VerifyError
        // on-device (plugin class rejected → "module not found" cascade).
        val luaLoaderClass = OnPurchasesUpdatedFingerprint.classDef
        val morpheFakePurchase = ImmutableMethod(
            LUA_LOADER,
            "morpheFakePurchase",
            listOf(
                ImmutableMethodParameter(
                    "Ljava/lang/String;",
                    null,
                    null
                )
            ),
            "V",
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(7)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # ── BillingResult OK into v0 (live through the dispatch) ──
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v0
                invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v0
                # ── WIDE pair v1/v2 = purchaseTime (untouched until append) ──
                invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                move-result-wide v1
                # ── StringBuilder into v3, v4 = dedicated string scratch ──
                new-instance v3, Ljava/lang/StringBuilder;
                invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
                const-string v4, "{\"orderId\":\"morphe.fake."
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\",\"productId\":\""
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\",\"purchaseTime\":"
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, ",\"purchaseState\":0,\"purchaseToken\":\"morphe.token."
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-static {}, Ljava/lang/System;->nanoTime()J
                move-result-wide v1
                invoke-virtual {v3, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\",\"packageName\":\"com.rebelbinary.onlyone\",\"quantity\":1}"
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v3
                # ── new Purchase(json=v3, signature=v4) — wide pair now dead ──
                const-string v4, "$FORGED_SIGNATURE"
                new-instance v1, Lcom/android/billingclient/api/Purchase;
                invoke-direct {v1, v3, v4}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
                # ── deliver through the stock sink: (this=v5, v0, v2) ──
                invoke-static {v1}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v2
                invoke-virtual {v5, v0, v2}, $LUA_LOADER->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                return-void
                nop
            """.trimIndent())
        }
        luaLoaderClass.methods.add(morpheFakePurchase)

        // ── 3a. Hook purchaseType: two-site straight-line guards ────────
        val purchaseTypeMethod = LuaLoaderPurchaseTypeFingerprint.method

        // Locate `sget-object v0, ...LuaLoader;->fCachedProductDetails:` —
        // the first read of the details cache (smali line 1813).
        val cacheGetIndex = purchaseTypeMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.let { ref ->
                ref.definingClass == LUA_LOADER && ref.name == "fCachedProductDetails"
            } ?: false
        }

        // The options-table path merges into the sget via an unconditional
        // GOTO whose target is the sget — locate the LAST GOTO before the
        // sget (nothing but const/fall-through lives between them).
        val tablePathGotoIndex = purchaseTypeMethod.indexOfFirstInstructionReversedOrThrow(cacheGetIndex) {
            opcode == Opcode.GOTO
        }

        // Register contract at BOTH sites: v2 = this (aliased from p0 at the
        // method head), v5 = productId, v7 = 0. No labels/branches → safe in
        // a method with exception tables. Insert at the higher index first so
        // the lower index stays valid.
        val purchaseGuard = """
            invoke-direct {v2, v5}, $LUA_LOADER->morpheFakePurchase(Ljava/lang/String;)V
            return v7
        """.trimIndent()
        purchaseTypeMethod.addInstructions(cacheGetIndex, purchaseGuard)
        purchaseTypeMethod.addInstructions(tablePathGotoIndex, purchaseGuard)

        // ── 3b. Hook LuaLoader$8.onProductDetailsResponse ───────────────
        // p2 is dead at entry; p1 (BillingResult) only feeds unreachable
        // error branches. this$0 = p0. Straight-line, no labels.
        val detailsResponseMethod = ProductDetailsResponseFingerprint.method
        detailsResponseMethod.addInstructions(0, """
            invoke-direct {p0, p2}, $LUA_LOADER->morpheFakePurchase(Ljava/lang/String;)V
            return-void
        """.trimIndent())
    }
}

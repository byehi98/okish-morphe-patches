package app.progressbar95.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.returnEarly
import app.progressbar95.patches.shared.Constants.COMPATIBILITY_PROGRESSBAR95
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Smali class descriptors (all stable SDK names — verified in smali).
// The \$ escapes keep Kotlin string interpolation from treating
// "$Builder" / "$8" as template expressions. NOTE: the trailing ';' is part
// of each descriptor — dropping it leaves an unterminated type that makes
// InlineSmaliCompiler report cascade lexer errors downstream
// (see BurritoBisonFreePurchasePatch).
private const val PURCHASE = "Lcom/android/billingclient/api/Purchase;"
private const val BILLING_RESULT = "Lcom/android/billingclient/api/BillingResult;"
private const val BILLING_RESULT_BUILDER = "Lcom/android/billingclient/api/BillingResult\$Builder;"
private const val LUA_LOADER = "Lplugin/google/iap/billing/v2/LuaLoader;"
private const val LUA_LOADER_8 = "Lplugin/google/iap/billing/v2/LuaLoader\$8;"

/**
 * Progressbar95 1.1110 · **Unlock AdFree+ (true free-store)** — hunter T1+T8.
 *
 * Corona/Solar2D Lua game: all purchase state lives in compiled Lua, but Lua
 * learns about purchases ONLY via `StoreTransactionRuntimeTask` events from
 * this bridge — so forging one for the requested SKU unlocks exactly what a
 * genuine purchase would (Lua already handles `purchased` + `restoreCompleted`).
 *
 * Why T8 (this patch) instead of the old T3 prepend: the T3 hook sat on
 * `onPurchasesUpdated` — DOWNSTREAM of Play — so every tap opened the Play
 * sheet first (fail popup), and only then did the forged event grant the
 * item. T8 intercepts UPSTREAM at the Lua→purchase entry (`purchaseType`),
 * dispatches the forged purchased event for the requested SKU and returns 0
 * immediately, so `launchBillingFlow` never runs: tap → instant grant, no
 * sheet, no fail popup.
 *
 * Three hooks, same execute block (OnlyOne `IapBillingBypass` precedent):
 *  1. T1 `Security.verifyPurchase → true` — the RSA receipt check always
 *     passes (headsoccer VerifyPurchase precedent). The forged row funnels
 *     through `onPurchasesUpdated`, which calls `verifyPurchase` per row.
 *  2. `morpheFakePurchase(String sku)` injected into `LuaLoader` (`.registers
 *     7`, straight-line, NO labels — `purchaseType` has try/catch regions for
 *     accountId/profileId hashing, same constraint as OnlyOne/BurritoBison):
 *     builds an OK `BillingResult`, forges `new Purchase(json, signature)`
 *     with the proven receipt shape (`purchaseState:1`, nanoTime-unique
 *     orderId/token — the shipped T3 JSON already grants on-device, so its
 *     shape is kept as-is with the requested SKU substituted), wraps it in a
 *     singleton list and calls `this.onPurchasesUpdated(...)`.
 *     Wide-register rule (OnlyOne VerifyError lesson): the timestamp long
 *     pair v1/v2 must not share a half with a string scratch — v4 is the
 *     dedicated string scratch, v3 the builder/JSON/Purchase, v0 the live OK
 *     result. All registers ≤ v15 → every 35c invoke encodable.
 *  3. Guard A+B in `purchaseType` (straight-line, no labels): locate the
 *     `SGET_OBJECT ... fCachedProductDetails` + the LAST `GOTO` before it
 *     (the options-table path merges into the sget via an unconditional
 *     `goto :goto_1b1`, so a single insertion before the sget would be jumped
 *     over). Insert `invoke-direct {v7, v4} + return v8` at the higher index
 *     first. Covers inapp AND subs (both funnel through `purchaseType`).
 *  4. Guard C in `LuaLoader$8.onProductDetailsResponse` at index 0
 *     (straight-line): `iget-object sku, val$productId` + `invoke-direct`
 *     via `this$0` + `return-void`. Belt-and-braces for the uncached path.
 *
 * The old T3 `onPurchasesUpdated` prepend is deliberately NOT present here —
 * keeping it would double-dispatch every tap (once from the guard, once from
 * the later restore/cancel callback).
 *
 * Register contract at both purchaseType guard sites (smali-verified,
 * re-derived for this app — NOT copied from OnlyOne): v7 = this (aliased at
 * the head, line 1300, never rewritten), v4 = productId (line 1339; null path
 * returns at 1349; next write is line 2008, after both guards), v8 = 0
 * (line 1309; every early exit and the tail return v8, never rewritten).
 *
 * Pure DEX — no native edit (no IL2CPP here).
 */
@Suppress("unused")
val progressbar95UnlockAdFreePlusPatch = bytecodePatch(
    name = "Free Store",
    description = "Tap any shop item to get it free. No payment, no Google Play popup.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PROGRESSBAR95)

    execute {
        // ── 1. Open the signature gate ─────────────────────────────────
        // T1: RSA signature check always passes, so the forged rows delivered
        // by morpheFakePurchase dispatch as storeTransaction events.
        VerifyPurchaseFingerprint.method.returnEarly(true)

        // ── 2. Inject the forged-purchase helper into LuaLoader ──────────
        // .registers 7 → locals v0-v4, p0=v5 (this), p1=v6 (sku).
        // WIDE-REGISTER RULE: v1/v2 hold the timestamp long; NOTHING may
        // write v1 or v2 between move-result-wide and its last append(J) use.
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
                invoke-static {}, $BILLING_RESULT->newBuilder()$BILLING_RESULT_BUILDER
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $BILLING_RESULT_BUILDER->setResponseCode(I)$BILLING_RESULT_BUILDER
                move-result-object v0
                invoke-virtual {v0}, $BILLING_RESULT_BUILDER->build()$BILLING_RESULT
                move-result-object v0
                # ── WIDE pair v1/v2 = purchaseTime (untouched until appends) ──
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
                const-string v4, "\",\"packageName\":\"com.spookyhousestudios.progressbar95\",\"productId\":\""
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\",\"productIds\":[\""
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\"],\"purchaseTime\":"
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, ",\"purchaseState\":1,\"purchaseToken\":\"morphe.token."
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\",\"token\":\"morphe.token."
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                move-result-object v3
                const-string v4, "\",\"quantity\":1,\"acknowledged\":true}"
                invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                move-result-object v3
                invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v3
                # ── new Purchase(json=v3, signature=v4) — wide pair now dead ──
                const-string v4, ""
                new-instance v1, $PURCHASE
                invoke-direct {v1, v3, v4}, $PURCHASE-><init>(Ljava/lang/String;Ljava/lang/String;)V
                # ── deliver through the stock sink: (this=v5, v0, v2) ──
                invoke-static {v1}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v2
                invoke-virtual {v5, v0, v2}, $LUA_LOADER->onPurchasesUpdated(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                return-void
                nop
            """.trimIndent())
        }
        luaLoaderClass.methods.add(morpheFakePurchase)

        // ── 3. Hook purchaseType: two-site straight-line guards ──────────
        val purchaseTypeMethod = LuaLoaderPurchaseTypeFingerprint.method

        // Locate `sget-object v0, ...LuaLoader;->fCachedProductDetails:` —
        // the first read of the details cache (smali line 1805).
        val cacheGetIndex = purchaseTypeMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.let { ref ->
                ref.definingClass == LUA_LOADER && ref.name == "fCachedProductDetails"
            } ?: false
        }

        // The options-table path merges into the sget via an unconditional
        // GOTO (`goto :goto_1b1`, smali line 1798) — locate the LAST GOTO
        // before the sget (nothing but const/fall-through lives between them).
        val tablePathGotoIndex = purchaseTypeMethod.indexOfFirstInstructionReversedOrThrow(cacheGetIndex) {
            opcode == Opcode.GOTO
        }

        // Register contract at BOTH sites: v7 = this, v4 = productId,
        // v8 = 0. No labels/branches → safe in a method with exception
        // tables. Insert at the higher index first so the lower stays valid.
        val purchaseGuard = """
            invoke-direct {v7, v4}, $LUA_LOADER->morpheFakePurchase(Ljava/lang/String;)V
            return v8
        """.trimIndent()
        purchaseTypeMethod.addInstructions(cacheGetIndex, purchaseGuard)
        purchaseTypeMethod.addInstructions(tablePathGotoIndex, purchaseGuard)

        // ── 4. Hook LuaLoader$8.onProductDetailsResponse ──────────────────
        // v0/v1 are dead at entry (method head writes first). The requested
        // SKU comes from the synthetic val$productId field, dispatched via
        // the this$0 LuaLoader. Straight-line, no labels.
        val detailsResponseMethod = ProductDetailsResponseFingerprint.method
        detailsResponseMethod.addInstructions(0, """
            iget-object v0, p0, $LUA_LOADER_8->val${'$'}productId:Ljava/lang/String;
            iget-object v1, p0, $LUA_LOADER_8->this${'$'}0:$LUA_LOADER
            invoke-direct {v1, v0}, $LUA_LOADER->morpheFakePurchase(Ljava/lang/String;)V
            return-void
        """.trimIndent())
    }
}

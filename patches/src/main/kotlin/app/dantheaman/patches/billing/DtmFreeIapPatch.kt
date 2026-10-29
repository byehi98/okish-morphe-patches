package app.dantheaman.patches.billing

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Dan the Man — Free IAP.
 *
 * The purchase flow in this engine is: native C++ (`IAP_Support` in
 * libmortargame.so) calls, through `JNIWrapper::BillingManagerAndroidWrapper`,
 * the Java bridge `Lcom/halfbrick/mortar/BillingManager;`:
 *
 *   native → DoPurchase(sku)
 *     └─ MortarGameActivity.sActivity.runOnUiThread(new BillingManager$8(sku))
 *          └─ $8.run() → g_billingManager.initiatePurchaseFlow(sku, "inapp")
 *              └─ (real Google Play billing flow) → onPurchasesUpdated →
 *                 purchase consumed → UpdateListener.onConsumeFinished →
 *                 synchronized(GetSyncObj()) { BeginCallNativeCode();
 *                 ConsumedResponse(0, token, orderId, sku, "");
 *                 EndCallNativeCode(); }
 *
 * `ConsumedResponse(I …)` is the ONLY data channel back to the engine. There is
 * no signature check anywhere: the Java-side key constant is the stock
 * `CONSTRUCT_YOUR_KEY_AND_PLACE_IT_HERE` placeholder, and no receipt/validate
 * strings exist in libmortargame.so — the stock success path itself relies
 * solely on `ConsumedResponse(responseCode=0, …)`. Faking that exact call is
 * therefore sufficient for the engine (`IAP_Support::GetLastPurchaseResult*`)
 * to grant the SKU.
 *
 * Two hooks, both in the Java bridge (keeping `BillingManager.Initialize` and
 * the whole Play Billing SDK untouched so `OnStoreDisconnected` semantics are
 * unchanged):
 *
 *   1. GRANT  — `BillingManager$8.run()` (the runOnUiThread body of
 *      DoPurchase) is rewritten to call the injected `DtmFakePurchase(sku)`
 *      helper: synchronized(GetSyncObj()) { BeginCallNativeCode();
 *      ConsumedResponse(0, "morphe-<millis>", "GPA.morphe-<millis>", sku, "");
 *      EndCallNativeCode(); }. The engine — which blocks waiting for the
 *      handshake, exactly like the rewarded-ad flow patched by
 *      DtmRemoveAdsPatch — receives the grant and unlocks the store flow
 *      without any payment sheet. Token/orderId are made unique per tap,
 *      mirroring real Play tokens.
 *
 *   2. STORE UI — `BillingManager.GetProductInfo(sku)` normally defers to the
 *      Play catalogue (skuPending / queryProductDetailsAsync →
 *      DetailsCallback(...)). On installs without a Play account/state
 *      (e.g. Waydroid) that query never resolves and the store shows no prices.
 *      It is rewritten to call the injected `DtmFakeProductInfo(sku)` helper:
 *      the same handshake immediately reporting `DetailsCallback(0, sku,
 *      "$0.00", "USD")`, so every store entry renders and is instantly
 *      purchasable.
 *
 * Both helpers are NEW static methods on BillingManager with their own register
 * files (MutableMethodImplementation — no exception table), so the raw native
 * handshake smali merges cleanly; the shims left in the real methods are
 * 1-3 instruction, label-free straight lines sized to the proven .registers of
 * each target ($8.run = 4, GetProductInfo = 4).
 *
 * ACCESS NOTE (device-verified crash): the helpers must be PUBLIC. The $8.run
 * shim lives in a different dex class, and dex access control is per class —
 * invoke-static to a `private static` member of another class throws
 * IllegalAccessError at link time (the stock code uses R8 `access$...` bridges
 * for the same reason). First on-device attempt shipped with PRIVATE and
 * crashed instantly with `IllegalAccessError: DtmFakePurchase is inaccessible
 * to class BillingManager$8`.
 */
@Suppress("unused")
val dtmFreeIapPatch = bytecodePatch(
    name = "Free IAP",
    description = "Every store item — gold packs, skins and remove ads — is granted the moment you tap Buy. No Google Play payment sheet ever opens and nothing is charged.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DTM)

    execute {
        // ────────────────────────────────────────────────────────────────────
        // Helper 1: fake successful purchase handshake for a sku.
        //
        // Java equivalent (register map in brackets):
        //   Object lock = NativeGameLib.GetSyncObj();            // [v0]
        //   synchronized (lock) {
        //       NativeGameLib.BeginCallNativeCode();
        //       long now = System.currentTimeMillis();           // [v4,v5]
        //       String token = "morphe-" + now;                  // [v1]
        //       String orderId = "GPA." + now;                   // [v3]
        //       ConsumedResponse(0, token, orderId, sku, "");    // [v2,p0,v6]
        //       NativeGameLib.EndCallNativeCode();
        //   }
        //
        // Mirrors UpdateListener.onConsumeFinished's success tail (the stock
        // code calls ConsumedResponse(billingResult.getResponseCode(),
        // purchase.getPurchaseToken(), purchase.getOrderId(),
        // purchase.getProducts().get(0), "") inside the same monitor), so the
        // engine sees byte-for-byte what a real consumed purchase delivers.
        // Register hygiene: v4/v5 is an exclusive wide pair (never used as an
        // invoke arg index).
        // ────────────────────────────────────────────────────────────────────
        val fakePurchase = ImmutableMethod(
            "Lcom/halfbrick/mortar/BillingManager;",
            "DtmFakePurchase",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), // p0 = sku
            "V",
            AccessFlags.STATIC.value or AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(9) // v0-v7 locals, p0 = v8 (sku)
        ).toMutable().apply {
            addInstructions(0, """
                invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->GetSyncObj()Ljava/lang/Object;
                move-result-object v0
                monitor-enter v0
                invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->BeginCallNativeCode()V
                new-instance v1, Ljava/lang/StringBuilder;
                invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
                const-string v3, "morphe-"
                invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                move-result-wide v4
                invoke-virtual {v1, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v1
                new-instance v3, Ljava/lang/StringBuilder;
                invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
                const-string v6, "GPA."
                invoke-virtual {v3, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v3, v4, v5}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
                invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v3
                const/4 v2, 0x0
                const-string v6, ""
                invoke-static {v2, v1, v3, p0, v6}, Lcom/halfbrick/mortar/BillingManager;->ConsumedResponse(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->EndCallNativeCode()V
                monitor-exit v0
                return-void
            """.trimIndent())
        }

        // ────────────────────────────────────────────────────────────────────
        // Helper 2: fake product-details callback for a sku.
        //
        // Same handshake, reporting DetailsCallback(0, sku, "$0.00", "USD")
        // (response code 0 = OK) so the store renders every item and its price
        // without ever contacting the Play catalogue.
        // ────────────────────────────────────────────────────────────────────
        val fakeProductInfo = ImmutableMethod(
            "Lcom/halfbrick/mortar/BillingManager;",
            "DtmFakeProductInfo",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), // p0 = sku
            "V",
            AccessFlags.STATIC.value or AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(9)
        ).toMutable().apply {
            addInstructions(0, """
                invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->GetSyncObj()Ljava/lang/Object;
                move-result-object v0
                monitor-enter v0
                invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->BeginCallNativeCode()V
                const/4 v1, 0x0
                const-string v2, "${'$'}0.00"
                const-string v3, "USD"
                invoke-static {v1, p0, v2, v3}, Lcom/halfbrick/mortar/BillingManager;->DetailsCallback(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->EndCallNativeCode()V
                monitor-exit v0
                return-void
            """.trimIndent())
        }

        val billingManagerClassFingerprint = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/BillingManager;",
            name = "GetProductInfo",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            returnType = "V",
            parameters = listOf("Ljava/lang/String;"),
            filters = listOf(
                fieldAccess(smali = "Lcom/halfbrick/mortar/BillingManager;->mIsServiceReady:Z")
            )
        ) {}

        val getProdInfo = billingManagerClassFingerprint.method
            ?: throw PatchException("DTM free IAP: BillingManager.GetProductInfo not found — app layout changed?")
        val billingClassDef = billingManagerClassFingerprint.classDef
            ?: throw PatchException("DTM free IAP: BillingManager classdef not found")
        billingClassDef.methods.add(fakePurchase)
        billingClassDef.methods.add(fakeProductInfo)

        // ── 1. GRANT: rewrite BillingManager$8.run() (the DoPurchase UI body) ──
        val purchaseRunFingerprint = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/BillingManager\$8;",
            name = "run",
            accessFlags = listOf(AccessFlags.PUBLIC),
            returnType = "V",
            parameters = listOf(),
            filters = listOf(
                fieldAccess(smali = "Lcom/halfbrick/mortar/BillingManager${'$'}8;->val${'$'}sku:Ljava/lang/String;")
            )
        ) {}

        val purchaseRun = purchaseRunFingerprint.method
            ?: throw PatchException("DTM free IAP: BillingManager\$8.run not found — DoPurchase inner-class layout changed?")

        purchaseRun.addInstructions(
            0,
            """
            iget-object v0, p0, Lcom/halfbrick/mortar/BillingManager${'$'}8;->val${'$'}sku:Ljava/lang/String;
            invoke-static {v0}, Lcom/halfbrick/mortar/BillingManager;->DtmFakePurchase(Ljava/lang/String;)V
            return-void
            """.trimIndent()
        )
        println("DTM free IAP: rewrote DoPurchase UI runnable (\$8.run) to instant-success handshake")

        // ── 2. STORE UI: rewrite GetProductInfo to the fake catalog callback ──
        getProdInfo.addInstructions(
            0,
            """
            invoke-static {p0}, Lcom/halfbrick/mortar/BillingManager;->DtmFakeProductInfo(Ljava/lang/String;)V
            return-void
            """.trimIndent()
        )
        println("DTM free IAP: rewrote GetProductInfo to instant DetailsCallback (0, sku, \$0.00, USD)")
    }
}

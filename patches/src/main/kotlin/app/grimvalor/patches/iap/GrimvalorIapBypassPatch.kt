package app.grimvalor.patches.iap

import app.grimvalor.patches.shared.Constants.COMPATIBILITY_GRIMVALOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

// Descriptors of the two nested handler interfaces contain `$`. They are held in
// `const val`s (ordinary Kotlin strings, where `\$` is a legal escape) and
// interpolated into the raw smali blocks, so the blocks themselves never contain
// a bare `$` and no `${'$'}` escaping is needed anywhere (see the `$` landmine
// note in .opencode/reference/traffic-rider-learnings.md §1.3).
private const val QUERY_HANDLER_DESC =
    "Lcom/direlight/androidservices/StoreHelper\$QueryPurchasesCompletionHandler;"
private const val PURCHASE_HANDLER_DESC =
    "Lcom/direlight/androidservices/StoreHelper\$PurchaseFlowCompletionHandler;"

/**
 * Grimvalor — full-game unlock (com.direlight.grimvalor v1.2.13,
 * Unity 6000.3.21f1 IL2CPP, Play Billing 9.1.0, distributed as an XAPK).
 *
 * ── Why the Java layer is enough ────────────────────────────────────────────
 * The billing bridge lives in the developer's own, **un-obfuscated** package
 * `com.direlight.androidservices` inside `classes2.dex` — it is a plain AAR the
 * studio dropped next to their Unity project, not R8 output. Its classes are:
 *
 *   `StoreController` — the object IL2CPP holds as an `AndroidJavaObject`
 *       (`NativeBillingClient._nativeStoreController`). Entry points
 *       (`connect`, `queueProductFetch`, `queryPurchases`, `purchaseProduct`,
 *       `consumePurchaseForTesting`) are called verbatim from C#.
 *   `StoreHelper` — thin wrapper over `com.android.billingclient.api`.
 *   `StorePurchase` / `StoreProduct` — plain data holders.
 *
 * **It is a pure relay. It performs no validation of any kind.**
 * `StorePurchase.signature` is written by all three constructors
 * (`StorePurchase.smali:135,158,235`) and **never read** — the class has no
 * `getSignature()` accessor at all. Across the whole APK the only methods ever
 * invoked on a `StorePurchase` are `getPurchaseState()` and `getProductId()`
 * (`StoreController$1.smali:74,81,97,111`, `StoreController$3.smali:155,162,
 * 178,192`, `StoreHelper.smali:812`), and the single gate is the trivial
 * `getPurchaseState() == 0` — `StorePurchase.STATE_PURCHASED` is `0`
 * (`StorePurchase.smali:11`). There is no receipt validation library, no
 * `UnityEngine.Purchasing.Security.dll`, no entitlement server and no
 * anti-tamper anywhere in the app. The entitlement decision itself is C#
 * (`IAPHandler.UnlockFullGame()` → the persisted `ClientData.fullGameUnlocked`
 * flag), and it is driven purely by the callback arguments the Java layer emits.
 * So: emit truthful-looking callbacks from Java and the game's own unlock path
 * runs, persists the flag and cloud-syncs it. No rollback risk.
 *
 * ── Why no native / metadata patching is involved ───────────────────────────
 * The natural place to inspect a Unity title is `libil2cpp.so` +
 * `global-metadata.dat`. Grimvalor's metadata is **format v39**, and the only
 * broadly available dumper (Il2CppDumper) tops out at v31 — there is no working
 * public tooling, and a bad metadata edit bricks the game. It is also
 * completely unnecessary: both hooks below live in the DEX layer, so the game
 * is patched without ever opening a native binary. That is the whole point of
 * choosing the relay over the engine.
 *
 * ── The two hooks ───────────────────────────────────────────────────────────
 * 1. **`StoreHelper.queryPurchases(QueryPurchasesCompletionHandler)V`**
 *    Normally asks Play which INAPP items are owned and relays the answer. It
 *    is invoked automatically on every launch (`IAPHandler.restorePurchasesOn
 *    Connect = true`), which makes this the primary hook.
 *
 *    Replaced body: build an `ArrayList<StorePurchase>` holding **one entry per
 *    key of `this.productDetailsMap`**, then hand it to the *untouched*
 *    `StoreController$3.onCompletion(List)`, which performs the real relay
 *    (`onStartedRestoringPurchases` → `onPurchaseRestored(id)` per entry →
 *    `onFinishedRestoringPurchases(true)`).
 *
 *    This is what makes the patch **product-id-agnostic**: `productDetailsMap`
 *    is filled by `fetchProducts` from the very ids C# itself queued via
 *    `queueProductFetch`, so the patch never has to know — or guess — the IAP
 *    product id (whose literal is not even recoverable from the v39 metadata).
 *
 *    Why ctor #3 is the magic: `StorePurchase.<init>(String, String, String,
 *    String)V` (`StorePurchase.smali:240-259`) assigns `itemType`, `json`,
 *    `orderId` and `productId` and **never assigns `purchaseState`**, so that
 *    field keeps its declared default of `0` = `STATE_PURCHASED`. Both relay
 *    classes therefore see a successful purchase. `getPurchaseTime()` is 0,
 *    `getToken()` is null — none of them are ever read.
 *
 * 2. **`StoreHelper.launchPurchaseFlow(Activity, String)V`**
 *    Normally validates the product id and opens the Play purchase sheet.
 *    Replaced body: synthesise the same kind of `StorePurchase` for `p2` (which
 *    *is* the id C# passed to `purchaseProduct(activity, id)`) and dispatch it
 *    to the already-registered `purchaseFlowCompletionHandler`. That lands in
 *    `StoreController$1.onCompleted`, whose `getPurchaseState() == 0` check
 *    routes it to `onPurchaseSucceeded(productId)` → `UnlockFullGame()`. This
 *    covers the case where the paywall *is* shown (e.g. hook #1 ran before the
 *    product list had been fetched).
 *
 * ── Register budgets (verified against the real smali) ─────────────────────
 * `addInstructionsWithLabels(0, …)` cannot change a method's `.registers`
 * count — `MutableMethodImplementation.registerCount` is final — so each body
 * is written to fit the frame the method already has. Morphe's
 * `InlineSmaliCompiler` resolves `pN` against that same count, so the original
 * numbering below is exact:
 *
 * - `queryPurchases` — `.registers 6`, one declared parameter
 *   → 4 true locals `v0..v3`; `p0` = `v4` (this), `p1` = `v5` (handler).
 *   `v0` = result `ArrayList` (live across the loop), `v1` = `hasNext()` result
 *   then the product id, `v2` = the `StorePurchase`, `v3` = the shared string
 *   constant. `p0` is read exactly once (`iget-object … productDetailsMap`) and
 *   is dead afterwards, so its register is reused as the iterator — one fewer
 *   register is needed and the method never has to grow.
 * - `launchPurchaseFlow` — `.registers 5`, two declared parameters
 *   → 2 true locals `v0..v1`; `p0` = `v2` (this), `p1` = `v3` (Activity),
 *   `p2` = `v4` (product id). `v0` = the `StorePurchase`, `v1` = the string
 *   constant then the completion handler, `p0` is dead after the `iget-object`
 *   and takes the empty error string.
 *
 * Every register index used is ≤ 5, so every `invoke` stays within the `35c`
 * form — no `/range`, no `move-object/from16`, and no `invoke-…/range` fallback.
 *
 * ── No injected helper method ───────────────────────────────────────────────
 * `traffic-rider-learnings.md` §1.2 requires an injected `morpheFakePurchase`
 * helper when patching `BillingClientImpl.launchBillingFlow` (billing 8.0.0),
 * because that method carries a 33-register try/catch exception table that
 * Morphe's label handling truncates. **That does not apply here.**
 * `StoreHelper` contains **zero** `.catch`/`.catchall` entries
 * (`rg -c '\.catchall|\.catch ' StoreHelper.smali` → 0), neither target has an
 * exception table, and Grimvalor never touches `BillingClientImpl` at all — it
 * calls the public `BillingClient.launchBillingFlow`. Injecting straight into
 * the two relay methods is therefore both safe and much simpler.
 *
 * ── Handler dropped on purpose ──────────────────────────────────────────────
 * The original `queryPurchases` builds `new Handler(Looper.getMainLooper())`
 * and passes it to `StoreHelper$3`. That handler exists **only** to marshal
 * Play's asynchronous `onQueryPurchasesResponse` — which arrives on a binder
 * thread — back onto the main looper (`StoreHelper$3.smali` posts
 * `StoreHelper$3$2` through `val$handler` before touching the completion
 * handler). The patched body never enters that async path: it calls
 * `completionHandler.onCompletion(list)` inline, and its caller is already the
 * Unity main thread (C# `IAPHandler.RestorePurchases` runs from the
 * main-looper-dispatched `onConnected` callback). Constructing the Handler
 * would be pure dead weight, so it is dropped.
 *
 * ── Failure behaviour ───────────────────────────────────────────────────────
 * Both fingerprints are guarded. If either fails to resolve — which is what a
 * future Grimvalor version would look like — the patch throws
 * [PatchException] instead of silently shipping a no-op that still advertises
 * "everything unlocked".
 */
@Suppress("unused")
val grimvalorIapBypassPatch = bytecodePatch(
    name = "Full game unlock",
    description = "Full Game Unlocked.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GRIMVALOR)

    execute {
        // ── Fail loudly rather than shipping a silent no-op ──────────────────
        val queryPurchases =
            StoreHelperQueryPurchasesFingerprint.matchOrNull()?.method
                ?: throw PatchException(
                    "Grimvalor full game unlock: StoreHelper.queryPurchases" +
                            "(QueryPurchasesCompletionHandler) not found — app layout changed?"
                )
        val launchPurchaseFlow =
            StoreHelperLaunchPurchaseFlowFingerprint.matchOrNull()?.method
                ?: throw PatchException(
                    "Grimvalor full game unlock: StoreHelper.launchPurchaseFlow" +
                            "(Activity, String) not found — app layout changed?"
                )

        // ── 1. queryPurchases — auto-grant every fetched product on restore ──
        // Injected at offset 0; the trailing return-void makes the untouched
        // original body (Play query + StoreHelper$3 listener) unreachable.
        // No try/catch here, so labelled blocks survive intact (§1.2 N/A).
        queryPurchases.addInstructionsWithLabels(
            0, """
            # ── MORPHE: build a synthetic "already owned" restore list ──
            # frame: .registers 6 → v0..v3 locals, p0=this(v4), p1=handler(v5)
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V

            # `this` is read exactly once and is dead afterwards, so p0's
            # register doubles as the key iterator.
            iget-object p0, p0, Lcom/direlight/androidservices/StoreHelper;->productDetailsMap:Ljava/util/HashMap;
            invoke-virtual {p0}, Ljava/util/HashMap;->keySet()Ljava/util/Set;
            move-result-object p0
            invoke-interface {p0}, Ljava/util/Set;->iterator()Ljava/util/Iterator;
            move-result-object p0

            :morphe_loop
            invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z
            move-result v1
            if-eqz v1, :morphe_emit
            invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v1
            check-cast v1, Ljava/lang/String;

            # Ctor #3 sets itemType/json/orderId/productId and leaves
            # purchaseState at its default 0 == STATE_PURCHASED, which is the
            # only gate either relay class applies. itemType/json/orderId are
            # never read back, so one constant register feeds all three.
            new-instance v2, Lcom/direlight/androidservices/StorePurchase;
            const-string v3, "inapp"
            invoke-direct {v2, v3, v3, v3, v1}, Lcom/direlight/androidservices/StorePurchase;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            invoke-virtual {v0, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            goto :morphe_loop

            :morphe_emit
            invoke-interface {p1, v0}, $QUERY_HANDLER_DESC->onCompletion(Ljava/util/List;)V
            return-void
            nop
        """.trimIndent()
        )

        // ── 2. launchPurchaseFlow — the paywall button grants instantly ─────
        // frame: .registers 5 → v0,v1 locals, p0=this(v2), p1=Activity(v3),
        // p2=productId(v4). p0 dies after the iget-object and takes the empty
        // error string; the Activity parameter is unused by this body.
        launchPurchaseFlow.addInstructionsWithLabels(
            0, """
            new-instance v0, Lcom/direlight/androidservices/StorePurchase;
            const-string v1, "inapp"
            invoke-direct {v0, v1, v1, v1, p2}, Lcom/direlight/androidservices/StorePurchase;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

            # PURCHASE_HANDLER_DESC already ends in a semicolon, which terminates
            # the field type descriptor. Appending a second literal semicolon here
            # would emit a doubled terminator and InlineSmaliCompiler rejects it
            # with "Error for input ';'", so no literal terminator follows the
            # interpolation.
            iget-object v1, p0, Lcom/direlight/androidservices/StoreHelper;->purchaseFlowCompletionHandler:$PURCHASE_HANDLER_DESC
            if-eqz v1, :morphe_done
            const-string p0, ""
            invoke-interface {v1, p2, v0, p0}, $PURCHASE_HANDLER_DESC->onCompleted(Ljava/lang/String;Lcom/direlight/androidservices/StorePurchase;Ljava/lang/String;)V
            :morphe_done
            return-void
            nop
        """.trimIndent()
        )

        println("Grimvalor full game unlock: patched StoreHelper.queryPurchases + StoreHelper.launchPurchaseFlow")
    }
}

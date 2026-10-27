package app.vitamahjong.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.vitamahjong.patches.shared.Constants.COMPATIBILITY_VITA_MAHJONG
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PATCH_NAME = "Vita Mahjong Restore Purchases"

/**
 * Frame of the replacement `c0.run()`: `v0..v4` locals, `p0 = v5`.
 *
 * The stock method is `.registers 1` (no local, no parameter — an instance
 * method with no arguments still reserves one register for `this`, and stock
 * `run()` never touches it). The fake callback needs an `ArrayList`, a
 * `PurchaseData` / `PurchaseModuleBridge$2`, a `String` scratch and a `long`
 * pair, so the frame grows to **6**.
 */
private const val RESTORE_RUN_REGISTERS = 6

/**
 * Full replacement body of `c0.run()V`.
 *
 * Two synthetic owned purchases are built and delivered through the *real*
 * `PurchaseModuleBridge$2.onSuccess(List)` listener, i.e. the exact code path
 * Play Billing would have driven:
 *
 *  * `m_ads_remove` — the non-consumable no-ads pack
 *  * `sub_1_year_vm` — the longest VIP subscription
 *
 * Token / order-id values are stable synthetic identities (`morphe-token`,
 * `GPA.morphe.*`, `morphe-inner-*`) so repeated restores do not look like new
 * purchases to the C# entitlement bookkeeping, and `purchaseTime` is wall-clock
 * `System.currentTimeMillis()` because the C# side compares it for expiry.
 *
 * No labels are needed — the body is straight-line.
 */
private val RESTORE_BODY = """
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V

    new-instance v1, Lcom/learnings/purchase/PurchaseData;
    invoke-direct {v1}, Lcom/learnings/purchase/PurchaseData;-><init>()V
    const-string v2, "m_ads_remove"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setProductId(Ljava/lang/String;)V
    const-string v2, "morphe-token"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setToken(Ljava/lang/String;)V
    const-string v2, "GPA.morphe.no_ads"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setOrderId(Ljava/lang/String;)V
    const-string v2, "morphe-inner-no-ads"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setInnerOrderId(Ljava/lang/String;)V
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    move-result-wide v3
    invoke-virtual {v1, v3, v4}, Lcom/learnings/purchase/PurchaseData;->setPurchaseTime(J)V
    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    new-instance v1, Lcom/learnings/purchase/PurchaseData;
    invoke-direct {v1}, Lcom/learnings/purchase/PurchaseData;-><init>()V
    const-string v2, "sub_1_year_vm"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setProductId(Ljava/lang/String;)V
    const-string v2, "morphe-token"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setToken(Ljava/lang/String;)V
    const-string v2, "GPA.morphe.vip"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setOrderId(Ljava/lang/String;)V
    const-string v2, "morphe-inner-vip"
    invoke-virtual {v1, v2}, Lcom/learnings/purchase/PurchaseData;->setInnerOrderId(Ljava/lang/String;)V
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    move-result-wide v3
    invoke-virtual {v1, v3, v4}, Lcom/learnings/purchase/PurchaseData;->setPurchaseTime(J)V
    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    new-instance v1, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;
    invoke-direct {v1}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;-><init>()V
    invoke-virtual {v1, v0}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;->onSuccess(Ljava/util/List;)V
    return-void
"""

/**
 * Vita Mahjong — Restore Purchases
 * (`com.vitastudio.mahjong` 3.44.1 / versionCode 1927, XAPK).
 *
 * ## What is forced
 *
 * On a fresh sideloaded install, "Restore Purchases" normally ends with Play
 * Billing reporting **zero owned products**, so the no-ads and VIP entitlements
 * stay off. Here the R8 synthetic Runnable that performs the query is replaced
 * wholesale with a body that reports two synthetic owned purchases through the
 * app's own success listener.
 *
 * ## Call chain
 *
 * ```
 * Unity C#
 *   → PurchaseModuleBridge.queryAllPurchases      (untouched: log + post)
 *     → UnityUtil.runOnUiThread(c0)               (untouched: async, UI thread)
 *       → c0.run()                                ← replaced here
 *           stock: PurchaseModuleBridge.d() → PurchaseManager.queryAllPurchases(listener)
 *           now:   build List<PurchaseData> → PurchaseModuleBridge$2.onSuccess(list)
 * ```
 *
 * ## Why `c0.run()` and not `queryAllPurchases()`
 *
 * If `queryAllPurchases()` itself were replaced, the callback would run
 * **synchronously** on whichever thread Unity invokes the bridge from. That is a
 * splash-time re-entrancy/crash risk (the C# side can call it during startup,
 * before the message handler is registered) and it loses the UI-thread affinity
 * every stock purchase callback has. Replacing only the Runnable body keeps the
 * stock `UnityUtil.runOnUiThread` hop, so:
 *
 *  * the fake callback is asynchronous, exactly like a real Play Billing result;
 *  * it runs on the UI thread, where `UnityUtil.unitySendMessage` is safe;
 *  * `queryAllPurchases()` and its log line are byte-for-byte stock.
 *
 * ## Threading
 *
 * `c0.run()` is only ever invoked by `UnityUtil.runOnUiThread`, so the injected
 * body — including `PurchaseModuleBridge$2.onSuccess` → `unitySendMessage` —
 * runs on the main/UI thread. No handler or post is needed, and none is added.
 *
 * ## Why `PurchaseModuleBridge` is not modified at all
 *
 * `c0` lives in the same package, `com.learnings.unity.unikit.module`, as
 * `PurchaseModuleBridge$2`, whose constructor is package-private. `c0.run()`
 * can therefore `new-instance PurchaseModuleBridge$2` directly. The old
 * implementation added a `PurchaseModuleBridge.morpheRestore()` static helper;
 * that required patching a class that otherwise needed no changes, and is gone.
 * `PurchaseModuleBridge` (and its `d()` and `queryAllPurchases()`) is left
 * entirely untouched — `PurchaseModuleBridge$2` stays referenced by `d()` and by
 * the new `c0.run()`, so it cannot be stripped.
 *
 * ## Deliberately NOT touched (DO NOT list)
 *
 *  * **Do not patch `queryAllPurchases()`** — see above; the async UI-thread
 *    hand-off is the safety property of this patch.
 *  * **Do not touch `PurchaseModuleBridge.d()`** — leaving it intact keeps the
 *    real Play Billing path available for other callers and keeps
 *    `PurchaseModuleBridge$2` referenced from its original site.
 *  * **Do not call `PurchaseManager` or Play Billing from the restore body** —
 *    the whole point is to avoid a network round trip that returns an empty
 *    owned-products list.
 *  * **Do not add helper methods** (`morpheRestore`, …) — the replacement is
 *    self-contained, so `PurchaseModuleBridge` gains no methods and no class is
 *    added to `classes8.dex`.
 *  * **Do not emit `onFail`** — a failure would make the C# side clear/ignore
 *    entitlements that are valid for the rest of the session.
 */
@Suppress("unused")
val vitaMahjongRestorePurchasesPatch = bytecodePatch(
    name = PATCH_NAME,
    description = "On a fresh install, \"Restore Purchases\" reports the no-ads pack and the yearly VIP subscription as already owned, so those entitlements activate without a real Google Play purchase.",
    default = true
) {
    compatibleWith(COMPATIBILITY_VITA_MAHJONG)

    execute {
        // ── Fail loudly rather than ship an APK with a silently skipped hook ──
        RestorePurchasesFingerprint.matchAllOrNull()
            .requireExactlyOne("PurchaseModuleBridge;->queryAllPurchases()V", PATCH_NAME)
        RestoreRunnableFingerprint.matchAllOrNull()
            .requireExactlyOne("Lcom/learnings/unity/unikit/module/c0;->run()V", PATCH_NAME)

        // ══ Replace c0.run() wholesale ═══════════════════════════════════════
        //
        // The stock body is a single `invoke-static PurchaseModuleBridge.d()`
        // with `.registers 1`. It is *replaced*, not preceded by an injected
        // `return`: `MutableMethod` has no `implementation` setter in
        // morphe-patcher 1.5.2 (`registerCount` is a `final` dexlib2 field), so
        // a fresh method object with a fresh 6-register implementation is built
        // and swapped into the class. Nothing of the stock body — and no
        // try/catch item, though `run()` has none — survives.
        val original = RestoreRunnableFingerprint.method
        val replacement = ImmutableMethod(
            original.definingClass,
            original.name,
            emptyList(),
            original.returnType,
            original.accessFlags,
            null,
            null,
            MutableMethodImplementation(RESTORE_RUN_REGISTERS)
        ).toMutable().apply {
            addInstructions(0, RESTORE_BODY.trimIndent())
        }

        val classDef = RestoreRunnableFingerprint.classDef
        val methodCountBefore = classDef.methods.size
        classDef.methods.apply {
            if (!remove(original)) {
                throw PatchException(
                    "$PATCH_NAME: could not remove c0.run() from ${original.definingClass} —" +
                        " the whole-body replacement would silently not apply."
                )
            }
            add(replacement)
        }

        // Read the result back out of the class: proves the swap landed and the
        // frame is the 6 registers the injected body addresses.
        val landed = classDef.methods.firstOrNull {
            it.name == original.name && it.returnType == original.returnType &&
                it.parameterTypes.isEmpty()
        } ?: throw PatchException(
            "$PATCH_NAME: ${original.definingClass}->${original.name} is missing after the" +
                " whole-body replacement."
        )

        if (landed.implementation?.registerCount != RESTORE_RUN_REGISTERS) {
            throw PatchException(
                "$PATCH_NAME: c0.run() has .registers ${landed.implementation?.registerCount}," +
                    " expected $RESTORE_RUN_REGISTERS. The injected body addresses registers" +
                    " that do not exist in that frame and would fail dex verification on device."
            )
        }

        if (landed.implementation?.tryBlocks?.isNotEmpty() == true) {
            throw PatchException(
                "$PATCH_NAME: c0.run() still carries a try/catch item after the replacement."
            )
        }

        if (classDef.methods.size != methodCountBefore) {
            throw PatchException(
                "$PATCH_NAME: ${original.definingClass} gained or lost methods during the" +
                    " replacement of run(); the patch must not add methods."
            )
        }
    }
}

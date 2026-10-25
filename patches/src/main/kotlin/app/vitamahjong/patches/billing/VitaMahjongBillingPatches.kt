package app.vitamahjong.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.vitamahjong.patches.shared.Constants.COMPATIBILITY_VITA_MAHJONG
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * Vita Mahjong — Restore Purchases (3.43.0 / versionCode 1917).
 *
 * `PurchaseModuleBridge.queryAllPurchases()V` normally logs, then posts the R8
 * synthetic Runnable `c0` through `UnityUtil.runOnUiThread`. `c0.run()` builds
 * the real `PurchaseModuleBridge$2` listener and asks Play Billing for owned
 * purchases. On a fresh sideloaded install Play returns an empty list, so the
 * no-ads/VIP entitlement looks unowned.
 *
 * We intentionally do **not** replace `queryAllPurchases` itself. Doing so made
 * the callback synchronous and re-entrant on the caller's thread, which is a
 * splash-time crash risk on devices where Unity invokes it during startup.
 * Instead:
 *
 *   - the original `queryAllPurchases` is left untouched (same log + same
 *     `UnityUtil.runOnUiThread(c0)` hand-off);
 *   - `c0.run()` is replaced with a call to a new static helper
 *     `PurchaseModuleBridge.morpheRestore()`;
 *   - the helper builds the synthetic owned-purchase list and invokes the real
 *     `PurchaseModuleBridge$2.onSuccess(List)` on the UI thread, exactly where
 *     the stock restore callback would have run.
 *
 * Synthetic owned purchases:
 *   - `m_ads_remove`  (non-consumable no-ads purchase, verified product id)
 *   - `sub_1_year_vm` (longest subscription, verified product id)
 *
 * Each `PurchaseData` gets stable synthetic identity values:
 *   token `morphe-token`, order ids `GPA.morphe.no_ads` / `GPA.morphe.vip`,
 *   inner order ids `morphe-inner-no-ads` / `morphe-inner-vip`, and
 *   `purchaseTime = System.currentTimeMillis()`.
 *
 * The helper is `public static` because it is invoked from the separate `c0`
 * class. Its `.registers 5` gives v0 (ArrayList), v1 (PurchaseData/callback),
 * v2 (String) and the v3/v4 wide pair for `currentTimeMillis`.
 */
@Suppress("unused")
val vitaMahjongRestorePurchasesPatch = bytecodePatch(
    name = "Vita Mahjong Restore Purchases",
    description = "On a fresh install, \"Restore Purchases\" reports the no-ads pack and the yearly VIP subscription as already owned, so those entitlements activate without a real Google Play purchase.",
    default = true
) {
    compatibleWith(COMPATIBILITY_VITA_MAHJONG)

    execute {
        // ══ Add the synthetic-restore helper to PurchaseModuleBridge ══════════
        // .registers 5 → v0 list, v1 PurchaseData/$2, v2 String, v3/v4 wide time.
        val morpheRestore = ImmutableMethod(
            "Lcom/learnings/unity/unikit/module/PurchaseModuleBridge;",
            "morpheRestore",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(5)
        ).toMutable().apply {
            addInstructions(0, """
                # ── v0 = list of synthetic owned purchases ──
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V

                # ══ m_ads_remove (no-ads entitlement) ══
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

                # ══ sub_1_year_vm (yearly VIP subscription) ══
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

                # ── deliver OnQueryAllPurchases(_list) to Unity on the UI thread ──
                new-instance v1, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;
                invoke-direct {v1}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;-><init>()V
                invoke-virtual {v1, v0}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge${'$'}2;->onSuccess(Ljava/util/List;)V

                return-void
            """.trimIndent())
        }
        RestorePurchasesFingerprint.classDef.methods.add(morpheRestore)

        // ══ Replace c0.run() with the helper call, preserving the UI thread ══
        // The stock run() calls PurchaseModuleBridge.d(); replace the whole body
        // with a straight-line invoke so no stale Play Billing call remains.
        val restoreRunnable = RestoreRunnableFingerprint.method
        val restoreRunnableStub = ImmutableMethod(
            restoreRunnable.definingClass,
            "run",
            emptyList(),
            "V",
            restoreRunnable.accessFlags,
            null,
            null,
            MutableMethodImplementation(1)
        ).toMutable().apply {
            addInstructions(0, """
                invoke-static {}, Lcom/learnings/unity/unikit/module/PurchaseModuleBridge;->morpheRestore()V
                return-void
            """.trimIndent())
        }
        RestoreRunnableFingerprint.classDef.methods.apply {
            remove(restoreRunnable)
            add(restoreRunnableStub)
        }
    }
}

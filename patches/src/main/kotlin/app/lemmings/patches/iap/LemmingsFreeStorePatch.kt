package app.lemmings.patches.iap

import app.lemmings.patches.shared.Constants.COMPATIBILITY_LEMMINGS
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile

/**
 * Lemmings: Puzzle Survival 9.4.4 · **Free Store** — store taps grant the
 * items with nothing charged, no Play sheet, and no failure popup. This is
 * the merge of the two proven device-tested patches:
 *
 * * "Lemmings: Free Store" (`onActivityResult` fabrication — PATCH 2), and
 * * "Lemmings: No Payment Popups" (`onCreate` sheet-suppression gate + native
 *   fail-popup RET via inline `dependsOn(rawResourcePatch)`).
 *
 * Unity IL2CPP title: the store tap travels native → `BillingClientImpl` →
 * `ProxyBillingActivity` → Play sheet. On the re-signed APK Play always
 * rejects (`responseCode: 5` DeveloperError, observed live in Waydroid
 * logcat). The patch converts the failure into a synthetic SUCCESS broadcast
 * carrying fabricated `Purchase` entries, and removes the UI around it:
 *
 * ```
 * BEFORE: tap → Play sheet → dismiss → FAIL popup → success popup → grant
 * AFTER:  tap → (no sheet, HALF 2) → forged grant → success popup → grant
 *                          ↳ fallback: sheet dismissed → onActivityResult
 *                            converts the failure to a grant (HALF 3)
 *                          ↳ safety net: native fail-popup dispatcher RETs
 *                            instantly if C# ever shows it (HALF 1)
 *   → BillingBroadcastManager (zzaa.onReceive)
 *     → PurchasesUpdatedListener.onPurchasesUpdated(OK, [6 Purchase])
 *       → C# success path → ProcessPurchase × 6 → grants
 * ```
 *
 * ============================================================================
 * HALF 1 — NATIVE fail-popup kill (static libil2cpp.so edit)
 * ============================================================================
 * Target (capstone-verified, see
 * `analysis/com.sadpuppy.lemmings/notes/popup-kill.md` TARGET A):
 *
 * * `RockCakes.Purchases.PurchaseReceiptManager::ShowPurchaseFailedPopup` —
 *   the CENTRAL fail-popup dispatcher (8 UI call sites converge here). Body
 *   calls `RewardManager.FireFailedPurchase()` then tail-calls the leaf popup.
 * * arm64 RVA **0x01EE9338** · file offset **0x1EE5338**
 *   (`file_off = RVA − 0x4000`, PH2 RX bias — notes §"ELF RVA→file-offset").
 * * Entry bytes (shipped lib, md5 `de8740995c925d69e925f30b5c9f1699`,
 *   re-verified with `xxd` before writing):
 *   `FE 4F BF A9  F3 03 00 AA  E0 03 1F AA`
 *   (`stp x30,x19,[sp,#-16]! ; mov x19,x0 ; mov x0,xzr`).
 * * Patch: word0 `FE4FBFA9` → `C0035FD6` (`ret`) — the method returns
 *   immediately; `FireFailedPurchase` + the leaf popup never run.
 * * Guard: the first 12 bytes must match exactly (4th word is a `bl` —
 *   relocation-sensitive, excluded from the mask). Mismatch → fall back to the
 *   BACKUP site; both mismatch → loud [PatchException], never a blind write.
 *
 * BACKUP (leaf, 1 caller — A1's tail-call, so A1 ⊇ A2):
 * `RockCakes.Purchases.PurchaseFailedPopupController::ShowPurchaseFailedPopup`,
 * arm64 RVA **0x01EE6C40** · file offset **0x1EE2C40**, word0 `FF0301D1`
 * (`sub sp,sp,#0x40`) → `C0035FD6` (`ret`), 16-B anchor (no relocations).
 *
 * Success path provably untouched: `ShowRewardReceipt` @0x01EE8F10 and
 * `ProcessPurchase` @0x01EEDC54 are separate methods; the write is 4 bytes at
 * one file offset. Only `lib/arm64-v8a/libil2cpp.so` is edited; the armv7 lib
 * has NO hunter-verified anchor (different ISA), so it is left byte-identical
 * (logged, not fatal — arm64 covers all modern devices, and the DEX halves
 * still convert failures to grants on armv7).
 *
 * Delivery: inline `dependsOn(rawResourcePatch{…})` (CrossyRoad/DeadTrigger
 * shape) — one listing entry, native edit runs first, RAW_ONLY forces lib/
 * extraction so `get("lib/<abi>/libil2cpp.so", true)` resolves.
 *
 * ============================================================================
 * HALF 2 — DEX Play-sheet suppression (onCreate gate, fixed shape)
 * ============================================================================
 * Reuses `ProxyBillingCreateFingerprint` (`Fingerprints.kt`) and the PROVEN
 * PATCH 2 fabrication bytes (millis-unique orderId / purchaseToken /
 * purchaseTime, 6 real catalog SKUs, RESPONSE_CODE=0,
 * INTENT_SOURCE=LAUNCH_BILLING_FLOW) — straight-line code only, no branches.
 *
 * Two fixes vs the deleted PATCH 1 (which VerifyError'd at class load —
 * `invalid branch target 331 (-> 0x158)`, i.e. a forward branch spanning a
 * ~300-instruction block inserted at index 1 resolved out of bounds):
 *  1. The gate tests **`p1`** (savedInstanceState: null = fresh tap →
 *     suppress; non-null = recreation → original body), never the
 *     uninitialized local `v1` PATCH 1 read at idx 1.
 *  2. Insert at index **0**, never 1: the patched-output decode proved that
 *     labels defined in an `addInstructions(1, …)` block resolve WITHOUT the
 *     insertion base — every one of them landed exactly 3 code units early
 *     (the size of the pre-existing idx-0 `invoke-super`), i.e. the snippet is
 *     assembled standalone (base 0) and spliced without fixup. Near 21t
 *     branches AND far gotos were all affected (the gate silently fell through
 *     to the original body). At idx 0, rel == absolute, so labels resolve
 *     correctly (PATCH 2 precedent). The forge path therefore issues its OWN
 *     explicit `invoke-super onCreate(p1)` (p1 is null there — exactly what a
 *     fresh start passes) so `SuperNotCalledException` can never fire, while
 *     the passthrough path falls through to the ORIGINAL super call — every
 *     path calls super exactly once.
 *  3. NO far `goto` in this half: a `goto` whose target is the end-of-block
 *     label is also at risk from the same base/promotion path, so every far
 *     transfer here is a NATIVE 22t `if-ne` (±32k code units, no promotion
 *     involved) over a tautology (`if-ne v5, v4` with v4=0/v5=1 — always
 *     taken), and every 21t conditional targets a label 1–2 instructions
 *     ahead:
 *
 * ```smali
 * # idx 0 insertion (base 0 — labels resolve correctly):
 * if-eqz p1, :fresh              # 21t near ✓ (fresh tap → suppress)
 * const/4 v4, 0x0                # (all v-regs dead at idx 0, safe to borrow;
 * const/4 v5, 0x1                #  original prologue redefines them anyway)
 * if-ne v5, v4, :passthrough     # 22t far, always taken ✓ (recreation → original)
 * :fresh
 * getIntent / hasExtra BUY_INTENT (pre-super: mIntent is set in attach(), safe)
 * if-nez v0, :forge               # 21t near ✓ (purchase flow → forge)
 * const/4 v4, 0x0
 * const/4 v5, 0x1
 * if-ne v5, v4, :passthrough     # 22t far, always taken ✓ (0x65/0x6e → original)
 * :forge
 * invoke-super onCreate(p1)       # explicit super (p1==null here) — exactly once
 * <PATCH 2 fabrication verbatim, straight-line, ends finish + return-void>
 * :passthrough
 * nop                              # END of injected block (in-block, like PATCH 2)
 * <original stream continues — super, prologue, try_start_9e, bounds intact>
 * ```
 *
 * The PATCH 2 requestCode preamble (`if-ne p1, v0`) is deliberately NOT
 * reused in this half: in `onCreate`, `p1` is a `Bundle` reference, and the
 * flow check above (BUY_INTENT extra) already selects purchase flows —
 * in-app-message (0x65) and first-party (0x6e) flows run the original body.
 *
 * ============================================================================
 * HALF 3 — DEX failure→grant fallback (onActivityResult, PATCH 2 verbatim)
 * ============================================================================
 * If the sheet ever still opens (recreation path, odd flow), dismissing it
 * lands in `onActivityResult` (`ProxyBillingResultFingerprint`), where the
 * requestCode-0x64 failure result is converted into the same forged grant.
 * This half is byte-for-byte the device-proven v2 block (first tap granted
 * live), kept verbatim INCLUDING its `goto :lemmings_passthrough`: at idx 0
 * the label base is correct, so this shape verifies and runs — do not
 * "improve" it into the 22t idiom; the 22t discipline applies to the onCreate
 * half above.
 *
 * One-shot history (dedup fix, kept): the first revision used HARDCODED
 * `orderId`/`purchaseToken`/`purchaseTime` + `acknowledged:true`, so repeat
 * taps broadcast `Purchase.equals`-equal duplicates the native side dropped
 * (`ProcessPurchaseIfNew`). Fixed by embedding `System.currentTimeMillis()`
 * in each `orderId`, `purchaseToken` and `purchaseTime` — every fabrication
 * is a NEW transaction. Genuine successes (`RESPONSE_CODE==0`) and non-0x64
 * flows (0x65 in-app messaging, 0x6e first-party) fall through untouched.
 *
 * The six injected SKUs are all REAL catalog IDs — two observed live in
 * logcat (`gold150k`, `unlimitedenergy`) and four from the game's own
 * metadata strings (`eggbundlepurchase`, `instructionslotbundle`,
 * `rewardrocketprizes1`, `superspinner`) — so the native
 * `ProcessPurchase: FAIL. Unrecognized product` guard passes.
 * `acknowledged:true` + `purchaseState:1` are kept: C# skips the Play
 * acknowledge call for fakes (a fake token would fail server-side).
 *
 * ============================================================================
 * REGISTER BUDGET & INJECTION SAFETY (both DEX halves)
 * ============================================================================
 * `onCreate` has `.registers 14`, `onActivityResult` `.registers 12`; both
 * patches sit at index 0 where all locals are dead, using only v0–v5
 * (redefined by the original prologue before any read on passthrough paths;
 * p-registers read, never clobbered). Every referenced method mirrors an
 * instruction already present in the original bodies (`getIntent`,
 * `hasExtra`, `getApplicationContext`, `getPackageName`, `sendBroadcast`,
 * `finish`) plus framework-only `System.currentTimeMillis`,
 * `String.valueOf(J)`, `StringBuilder` and `ArrayList` — and every extra key
 * mirrors the original broadcast (`INTENT_SOURCE=LAUNCH_BILLING_FLOW`). No
 * obfuscated `zz*` references anywhere.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * One `bytecodePatch` val, `default = true`, independently selectable, with
 * the native RET as an inline `dependsOn` (single listing entry).
 * `lemmingsVipUnlockPatch` (`LemmingsVipUnlockPatch.kt` — UNTOUCHED) covers
 * the subscription/VIP flag separately.
 *
 * Device test: tap a store offer → no sheet → items grant (success popup
 * only); dismiss-path and fail popup both dead.
 */
@Suppress("unused")
val lemmingsFreeStorePatch = bytecodePatch(
    name = "Lemmings: Free Store",
    description = "Everything in the shop is free. Tap an item and you get it right away — no Google Play payment sheet, no purchase-failed popup, nothing charged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LEMMINGS)

    // ── HALF 1 (native): fail-popup dispatcher → RET. Inline dependency so
    //    PatchLoader never lists it separately (CrossyRoad shape). ──
    dependsOn(
        rawResourcePatch(
            name = "Lemmings native fail-popup kill",
            description = "Skips the Google Play payment sheet and the purchase-failed popup. Store taps grant items instantly with no popups in the way. Works together with Lemmings: Free Store.",
            default = true,
        ) {
            compatibleWith(COMPATIBILITY_LEMMINGS)

            execute {
                val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
                if (!arm64.exists()) {
                    throw PatchException(
                        "Lemmings native popup kill: lib/arm64-v8a/libil2cpp.so absent — " +
                            "unexpected split layout?",
                    )
                }
                applyPopupAnchor(arm64)

                // armv7: NO hunter-verified anchor (Thumb-2 needs its own
                // analysis) — leave byte-identical, say so loudly. The DEX
                // halves still convert failures to grants there.
                val armv7 = get("lib/armeabi-v7a/libil2cpp.so", true)
                if (armv7.exists()) {
                    println(
                        "Lemmings native popup kill: lib/armeabi-v7a/libil2cpp.so present " +
                            "but no verified armv7 anchor — leaving untouched " +
                            "(arm64 patched; DEX halves active on all ABIs).",
                    )
                }
            }
        },
    )

    execute {
        // ── Shared fabrication tail: v0 = per-tap millis string, v1 = forged
        //    Intent, v2 = purchase-data list. Appends one JSON per SKU, then
        //    the signature list, RESPONSE_CODE=0, INTENT_SOURCE, broadcast,
        //    finish, return-void. Byte-identical in both DEX halves. ──
        val purchasesSmali = LEMMINGS_FAKE_SKUS.joinToString("\n") { fakePurchaseSmali(it) }
        val broadcastTail = """
            new-instance v3, Ljava/util/ArrayList;
            invoke-direct {v3}, Ljava/util/ArrayList;-><init>()V
            const-string v4, "lemmings"
            invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            const-string v4, "INAPP_PURCHASE_DATA_LIST"
            invoke-virtual {v1, v4, v2}, Landroid/content/Intent;->putStringArrayListExtra(Ljava/lang/String;Ljava/util/ArrayList;)Landroid/content/Intent;
            const-string v4, "INAPP_DATA_SIGNATURE_LIST"
            invoke-virtual {v1, v4, v3}, Landroid/content/Intent;->putStringArrayListExtra(Ljava/lang/String;Ljava/util/ArrayList;)Landroid/content/Intent;
            const-string v2, "RESPONSE_CODE"
            const/4 v3, 0x0
            invoke-virtual {v1, v2, v3}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;
            const-string v2, "INTENT_SOURCE"
            const-string v3, "LAUNCH_BILLING_FLOW"
            invoke-virtual {v1, v2, v3}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
            invoke-virtual {p0, v1}, Lcom/android/billingclient/api/ProxyBillingActivity;->sendBroadcast(Landroid/content/Intent;)V
            invoke-virtual {p0}, Lcom/android/billingclient/api/ProxyBillingActivity;->finish()V
            return-void
        """.trimIndent()

        // ── HALF 2 (DEX): onCreate sheet-suppression gate, idx 0 (see KDoc:
        //    idx-1 insertions mis-resolve every label by the pre-existing
        //    prefix size; only idx 0 is safe). The forge path carries its own
        //    explicit super call (p1 is null there); the passthrough path uses
        //    the original super — super runs exactly once on every path. Try
        //    region starts far below, so :try_start_9e and its handler shift
        //    uniformly, bounds intact. ──
        ProxyBillingCreateFingerprint.method.addInstructions(0, """
            if-eqz p1, :lemmings_create_fresh
            const/4 v4, 0x0
            const/4 v5, 0x1
            if-ne v5, v4, :lemmings_create_passthrough
            :lemmings_create_fresh
            invoke-virtual {p0}, Lcom/android/billingclient/api/ProxyBillingActivity;->getIntent()Landroid/content/Intent;
            move-result-object v0
            const-string v1, "BUY_INTENT"
            invoke-virtual {v0, v1}, Landroid/content/Intent;->hasExtra(Ljava/lang/String;)Z
            move-result v0
            if-nez v0, :lemmings_create_forge
            const/4 v4, 0x0
            const/4 v5, 0x1
            if-ne v5, v4, :lemmings_create_passthrough
            :lemmings_create_forge
            invoke-super/range {p0 .. p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v0
            invoke-static {v0, v1}, Ljava/lang/String;->valueOf(J)Ljava/lang/String;
            move-result-object v0
            new-instance v1, Landroid/content/Intent;
            const-string v2, "com.android.vending.billing.LOCAL_BROADCAST_PURCHASES_UPDATED"
            invoke-direct {v1, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
            invoke-virtual {p0}, Lcom/android/billingclient/api/ProxyBillingActivity;->getApplicationContext()Landroid/content/Context;
            move-result-object v2
            invoke-virtual {v2}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v2
            invoke-virtual {v1, v2}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
            new-instance v2, Ljava/util/ArrayList;
            invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
            $purchasesSmali
            $broadcastTail
            :lemmings_create_passthrough
            nop
        """.trimIndent())

        // ── HALF 3 (DEX): onActivityResult failure→grant fallback, idx 0.
        //    Converts any failure result that reaches onActivityResult
        //    (requestCode 0x64 purchase flow only; 0x65 in-app messaging and
        //    genuine RESPONSE_CODE==0 pass through). Verbatim PATCH 2 shape —
        //    do not restructure (see KDoc). ──
        ProxyBillingResultFingerprint.method.addInstructions(0, """
            const/16 v0, 0x64
            if-ne p1, v0, :lemmings_passthrough
            if-eqz p3, :lemmings_fabricate
            invoke-virtual {p3}, Landroid/content/Intent;->getExtras()Landroid/os/Bundle;
            move-result-object v0
            if-eqz v0, :lemmings_fabricate
            const-string v1, "RESPONSE_CODE"
            const/4 v2, 0x5
            invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->getInt(Ljava/lang/String;I)I
            move-result v0
            if-nez v0, :lemmings_fabricate
            goto :lemmings_passthrough
            :lemmings_fabricate
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v0
            invoke-static {v0, v1}, Ljava/lang/String;->valueOf(J)Ljava/lang/String;
            move-result-object v0
            new-instance v1, Landroid/content/Intent;
            const-string v2, "com.android.vending.billing.LOCAL_BROADCAST_PURCHASES_UPDATED"
            invoke-direct {v1, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
            invoke-virtual {p0}, Lcom/android/billingclient/api/ProxyBillingActivity;->getApplicationContext()Landroid/content/Context;
            move-result-object v2
            invoke-virtual {v2}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v2
            invoke-virtual {v1, v2}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
            new-instance v2, Ljava/util/ArrayList;
            invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
            $purchasesSmali
            $broadcastTail
            :lemmings_passthrough
            nop
        """.trimIndent())
    }
}

/** PRIMARY native kill site (TARGET A1 — dispatcher → RET at entry). */
private const val POPUP_PRIMARY_OFFSET = 0x1EE5338L
private const val POPUP_PRIMARY_ORIG = "FE4FBFA9F30300AAE0031FAA"
private const val POPUP_RET = "C0035FD6"

/** BACKUP native kill site (TARGET A2 — leaf popup → RET at entry). */
private const val POPUP_BACKUP_OFFSET = 0x1EE2C40L
private const val POPUP_BACKUP_ORIG = "FF0301D1FE5F01A9F65702A9F44F03A9"

/**
 * Applies the fail-popup RET: primary dispatcher first, leaf backup if the
 * primary anchor drifted. Throws [PatchException] (no write) if neither
 * anchor matches — a moved build must fail loudly, never corrupt the lib.
 */
private fun applyPopupAnchor(lib: File) {
    println("Lemmings native popup kill: patching ${lib.name} (${lib.length()} bytes)")
    if (tryAnchor(lib, POPUP_PRIMARY_OFFSET, POPUP_PRIMARY_ORIG, "ShowPurchaseFailedPopup dispatcher (A1)")) return
    println(
        "Lemmings native popup kill: primary anchor mismatch — " +
            "trying backup leaf site.",
    )
    if (tryAnchor(lib, POPUP_BACKUP_OFFSET, POPUP_BACKUP_ORIG, "ShowPurchaseFailedPopup leaf (A2)")) return
    throw PatchException(
        "Lemmings native popup kill: NEITHER anchor matched " +
            "(A1 file 0x${POPUP_PRIMARY_OFFSET.toString(16)}, " +
            "A2 file 0x${POPUP_BACKUP_OFFSET.toString(16)}). " +
            "libil2cpp.so layout changed — unsupported app version?",
    )
}

/** Reads the anchor, compares, writes RET in place. Returns true if applied. */
private fun tryAnchor(lib: File, offset: Long, originalHex: String, label: String): Boolean {
    RandomAccessFile(lib, "rw").use { raf ->
        if (raf.length() < offset + 4) return false
        raf.seek(offset)
        val anchorLen = originalHex.length / 2
        val actual = ByteArray(anchorLen)
        raf.readFully(actual)
        if (!actual.contentEquals(hexBytes(originalHex))) {
            println(
                "Lemmings native popup kill: $label anchor mismatch at file " +
                    "0x${offset.toString(16)} (found ${toHex(actual)}).",
            )
            return false
        }
        raf.seek(offset)
        raf.write(hexBytes(POPUP_RET))
        println(
            "Lemmings native popup kill: $label file 0x${offset.toString(16)}: " +
                "${originalHex.substring(0, 8)} -> $POPUP_RET (ret)",
        )
        return true
    }
}

/** Parses a plain hex string (no separators) into bytes. */
private fun hexBytes(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

/** Real catalog SKUs short names; full IDs are `com.sadpuppy.lemmings.<sku>`. */
private val LEMMINGS_FAKE_SKUS = listOf(
    "gold150k",
    "unlimitedenergy",
    "eggbundlepurchase",
    "instructionslotbundle",
    "rewardrocketprizes1",
    "superspinner",
)

/**
 * Smali appending one fabricated purchase JSON for [sku] to the list in v2.
 * Expects v0 = per-tap millis string (uniqueness source), v3/v4 = scratch.
 * `purchaseTime` embeds the millis raw (valid JSON number); `orderId` and
 * `purchaseToken` embed it between fixed affixes so no two taps produce
 * `Purchase.equals`-equal entries.
 */
private fun fakePurchaseSmali(sku: String): String = """
    new-instance v3, Ljava/lang/StringBuilder;
    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
    const-string v4, "{\"orderId\":\"lemmings-"
    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v4, "-$sku\",\"packageName\":\"com.sadpuppy.lemmings\",\"productId\":\"com.sadpuppy.lemmings.$sku\",\"purchaseTime\":"
    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v4, ",\"purchaseState\":1,\"purchaseToken\":\"lemmings-"
    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v4, "-$sku\",\"quantity\":1,\"acknowledged\":true}"
    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
""".trimIndent()

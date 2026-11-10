package app.lemmings.patches.iap

import app.lemmings.patches.shared.Constants.COMPATIBILITY_LEMMINGS
import app.lemmings.patches.shared.NativeBytePatcher
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File

/**
 * Lemmings: Puzzle Survival 9.5.1 · **Native Grant Hook** — store taps grant
 * the tapped item through the game's OWN reward chain, with Unity IAP never
 * entered at all, and the native fail-popup dispatcher is RET-killed (folded
 * inline dependency, single listing entry).
 *
 * **Scope for 9.5.1: arm64-v8a only.** The 9.5.1 APKPure XAPK this patch set
 * was rebased against ships base + `config.arm64_v8a` only (manifest
 * `split_configs: ["config.arm64_v8a"]`, versionCode 7952). APKPure also
 * publishes a separate armeabi-v7a variant, but that build was not obtained,
 * so no 9.5.1 armv7 offsets could be derived — the 9.5.0 armv7 addresses are
 * stale and are deliberately NOT applied (the loop warns instead of writing
 * to a drifted lib). 9.5.0 support (both ABIs) remains documented in git
 * history.
 *
 * Rebase method: the 9.5.0 arm64 lib (md5 502bd49e80824697bf376a2f0e4c8bb1)
 * and the 9.5.1 arm64 lib (md5 a67418b38a6d07e59c0c4a7dcd98a118, 71,240,224
 * bytes) were disassembled with capstone and each of the four sites below was
 * matched on its prologue + body shape. The matched functions are shifted by a
 * per-cluster constant:
 *   * store/grant cluster (`RockCakes.Store` + `RockCakes.Purchases`):
 *     **+0x25FA4**
 *   * fail-popup cluster: **+0x26194**
 * Both clusters keep their internal distances (hook→target stays 0x1874,
 * dispatcher→leaf stays 0x2684), so every relative branch encoding below is
 * byte-identical to 9.5.0 and only the absolute file offsets moved.
 *
 * Why the tap-path hijack (full analysis in
 * `analysis/com.sadpuppy.lemmings/notes/native-grant-hook.md`):
 *
 * Forged Google-Play purchases DO reach Unity IAP's `onPurchasesUpdated`, but
 * the Unity IAP 4.x C# layer silently drops them (Cpp2IL strips the callback
 * bodies — there is nothing to convince). Forging further is a dead end, so
 * the hook reroutes the tap itself:
 *
 * ```
 * BEFORE: tap → StoreElementController::IAPPurchase → Unity IAP
 *              → (billing sheet / forged receipt / server validation) → …
 * AFTER:  tap → IAPPurchase (first instr) → b PurchaseCompleted
 *              → ShowRewardReceipt → ApplyReward
 *              → RewardHandlerCurrency::HandleReward → GRANT
 * ```
 *
 * ============================================================================
 * TARGET — `RockCakes.Store.StoreElementController::IAPPurchase` (md16158)
 * ============================================================================
 * * The EXACT hook the real-money store button invokes on tap (2-arg
 *   overload `(this, bool)` — the second argument is unused by the hooked
 *   target).
 * * arm64 file offset **0x1EED5DC** for 9.5.1 (9.5.0 was **0x1EC7638**;
 *   `VA = file + 0x4000`, lib md5 `a67418b38a6d07e59c0c4a7dcd98a118`).
 *   Entry words `FE 5F BD A9 / F6 57 01 A9 / F4 4F 02 A9`
 *   (`stp x30,x23,[sp,#-0x30]! ; stp x22,x21,[sp,#0x10] ; stp x20,x19,[sp,#0x20]`).
 *   Patch: word0 → `1D 06 00 14` — arm64 `b #0x1EEEE50`, the entry of
 *   `PurchaseCompleted` (md16190, takes only `this`). Encoding check:
 *   `0x14000000 | ((0x1EEEE50 − 0x1EED5DC) >> 2) = 0x14000000 | 0x61D`
 *   — capstone-verified, unchanged from 9.5.0 because the two sites share the
 *   same +0x25FA4 shift. Relative branch, build-independent.
 * * Both hook into the method BEFORE its own `push`, so `x0` is still the
 *   controller instance and `x30` the caller's return address; the target
 *   builds and pops its own frame. Convention preserved: the target's internal
 *   null-guards always pass on a tappable button, and the old IAPPurchase body
 *   (analytics / mute UI prep) never runs.
 *
 * ============================================================================
 * READ-ONLY GUARD — `PurchaseCompleted` prologue (no write)
 * ============================================================================
 * * arm64: 16 bytes at **0x1EEEE50** must read
 *   `FF 43 01 D1 FE 13 00 F9 F6 57 03 A9 F4 4F 04 A9`
 *   (`sub sp,sp,#0x50 ; str x30,[sp,#0x20] ; stp x22,x21,… ; stp x20,x19,…`).
 *   Verified byte-identical to 9.5.0 at the shifted address.
 * * Checked BEFORE the branch write: if the grant target drifted layout,
 *   patching the caller blind would jump into garbage — fail loudly instead.
 *
 * ============================================================================
 * FOLDED DEPENDENCY — native fail-popup RET (popup-kill.md TARGET A/B)
 * ============================================================================
 * The `RockCakes.Purchases` fail-popup dispatcher and its leaf popup get the
 * same entry→return treatment via an inline `dependsOn(rawResourcePatch{…})`
 * (CrossyRoad/DeadTrigger shape) so grant hook + popup kill ship as ONE
 * listing entry ("Lemmings: Native Grant Hook") and the patcher applies the
 * native edit before this patch.
 *
 * * PRIMARY: `RockCakes.Purchases.PurchaseReceiptManager::ShowPurchaseFailedPopup`
 *   — the CENTRAL fail-popup dispatcher (8 UI call sites converge here).
 *   * arm64 file offset **0x1F6B640** for 9.5.1 (9.5.0 was 0x1F454AC,
 *     delta +0x26194), entry `FE 4F BF A9 F3 03 00 AA E0 03 1F AA`.
 *     word0 → `C0035FD6` (`ret`).
 * * BACKUP: `RockCakes.Purchases.PurchaseFailedPopupController::ShowPurchaseFailedPopup`
 *   — leaf, 1 caller (the dispatcher's tail-call `b`).
 *   * arm64 file offset **0x1F68FBC** for 9.5.1 (9.5.0 was 0x1F42E28),
 *     12-B anchor `FE0F1DF8 F65701A9 F44F02A9` (word3 is an `adrp`,
 *     excluded); word0 → `C0035FD6`. Byte-identical prologue at the shifted
 *     address.
 * * 9.5.1 dispatcher→leaf distance is still 0x2684, so the tail-call `b`
 *   from A1 to A2 needs no re-encoding.
 * * Neither anchor matches on an ABI → loud [PatchException], never a blind
 *   write.
 *
 * Success/popups provably untouched: `ShowRewardReceipt` (all 3 overloads),
 * `ApplyReward`, `ProcessPurchase` and the VIP pair are separate methods, and
 * the writes are 4 bytes each at fixed file offsets. The reward claim popup is
 * deliberately kept — it is the grant mechanism visible to the user (no
 * runtime logging added: the grant is device-visible via the popup + HUD
 * balance change; only the FAIL popup pair dies).
 *
 * Delivery: top-level `rawResourcePatch` (pure-native shape, same as
 * `lemmingsVipUnlockPatch`) with the popup kill as an inline dependency,
 * ONE listing entry "Lemmings: Native Grant Hook", RAW_ONLY forces lib/
 * extraction so both `get(…)` calls resolve.
 *
 * Device test: open the store, tap a real-money item (e.g. `gold12k`) →
 * the game's reward claim popup (NOT Play billing) + currency/egg grant in
 * the HUD; logcat shows NO `FireFailedPurchase` / `ProcessPurchase`, and a
 * failed checkout (if ever reached) shows no fail popup. Same on arm64 and on
 * an armeabi-v7a-only device.
 */
@Suppress("unused")
val lemmingsNativeGrantHookPatch = rawResourcePatch(
    name = "Lemmings: Native Grant Hook",
    description = "Store items grant instantly when tapped, through the game's own reward system — no purchase flow is ever started, nothing is charged, and purchase-failure popups are removed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LEMMINGS)

    // ── Folded native fail-popup kill: inline dependency so PatchLoader
    //    never lists it separately (CrossyRoad shape). ──
    dependsOn(
        rawResourcePatch(
            name = "Lemmings native fail-popup kill",
            description = "Removes the purchase-failure popup. Runs as part of Lemmings: Native Grant Hook.",
            default = true,
        ) {
            compatibleWith(COMPATIBILITY_LEMMINGS)

            execute {
                var patchedAbis = 0
                for (abi in LemmingsGrantAbi.all) {
                    val lib = get("lib/${abi.id}/libil2cpp.so", true)
                    if (!lib.exists()) {
                        println("Lemmings native popup kill: lib/${abi.id}/libil2cpp.so absent — skipping ABI.")
                        continue
                    }
                    applyPopupAnchor(lib, abi)
                    patchedAbis++
                }
                warnUnsupportedAbis("Lemmings native popup kill") { path ->
                    get(path, true)
                }
                if (patchedAbis == 0) {
                    throw PatchException(
                        "Lemmings native popup kill: no libil2cpp.so found in any supported ABI " +
                            "split (arm64-v8a) — unexpected split layout?",
                    )
                }
            }
        },
    )

    execute {
        var patchedAbis = 0
        for (abi in LemmingsGrantAbi.all) {
            val lib = get("lib/${abi.id}/libil2cpp.so", true)
            if (!lib.exists()) {
                println("Lemmings native grant hook: lib/${abi.id}/libil2cpp.so absent — skipping ABI.")
                continue
            }
            applyGrantHook(lib, abi)
            patchedAbis++
        }
        warnUnsupportedAbis("Lemmings native grant hook") { path ->
            get(path, true)
        }
        if (patchedAbis == 0) {
            throw PatchException(
                "Lemmings native grant hook: no libil2cpp.so found in any supported ABI split " +
                    "(arm64-v8a) — unexpected split layout?",
            )
        }
    }
}

/**
 * Logs a loud warning when an ABI split is present but has no verified 9.5.1
 * anchors. The patch leaves that lib untouched (never a blind write) so the
 * user knows the APK is arm64-only-patched instead of silently assuming both.
 */
private fun warnUnsupportedAbis(tag: String, resolve: (String) -> java.io.File) {
    for (abiId in LemmingsGrantAbi.unsupported) {
        val lib = runCatching { resolve("lib/$abiId/libil2cpp.so") }.getOrNull() ?: continue
        if (lib.exists()) {
            println(
                "$tag: WARNING lib/$abiId/libil2cpp.so is present but 9.5.1 $abiId offsets " +
                    "are not derived — this ABI is left UNPATCHED.",
            )
        }
    }
}

/**
 * Per-ABI anchor set for the tap-path grant hook: the branch site, the
 * read-only guard on the grant target, and the fail-popup pair + return idiom.
 */
private class LemmingsGrantAbi(
    val id: String,
    val versionNote: String,
    /** `StoreElementController::IAPPurchase` entry (word0 is replaced). */
    val hookOffset: Long,
    val hookOrig: String,
    val hookBranch: String,
    /** `StoreElementController::PurchaseCompleted` prologue (read-only guard). */
    val targetOffset: Long,
    val targetExpected: String,
    /** Fail-popup dispatcher (A1) — tried first. */
    val popupPrimaryOffset: Long,
    val popupPrimaryOrig: String,
    /** Fail-popup leaf (A2) — used when A1 drifted. */
    val popupBackupOffset: Long,
    val popupBackupOrig: String,
    /** Entry→return idiom for this ISA. */
    val retStub: String,
) {
    companion object {
        /** 9.5.1 arm64 (md5 a67418b38a6d07e59c0c4a7dcd98a118, VA = file + 0x4000). */
        val ARM64 = LemmingsGrantAbi(
            id = "arm64-v8a",
            versionNote = "9.5.1 arm64 (+0x25FA4 store/grant, +0x26194 popups vs 9.5.0)",
            hookOffset = 0x1EED5DCL,
            hookOrig = "FE5FBDA9F65701A9F44F02A9",
            hookBranch = "1D060014", // b #0x1EEEE50
            targetOffset = 0x1EEEE50L,
            targetExpected = "FF4301D1FE1300F9F65703A9F44F04A9",
            popupPrimaryOffset = 0x1F6B640L,
            popupPrimaryOrig = "FE4FBFA9F30300AAE0031FAA",
            popupBackupOffset = 0x1F68FBCL,
            popupBackupOrig = "FE0F1DF8F65701A9F44F02A9",
            retStub = "C0035FD6", // ret
        )

        /**
         * 9.5.0 armeabi-v7a (md5 e1fe5b5b8a45c02704dbb5aca6a22116, VA == file
         * offset). ARM state (A32), not Thumb: every prologue starts `E92D…`.
         *
         * NOT applied for 9.5.1: no 9.5.1 armv7 lib was obtained, so these
         * offsets are stale. Kept for reference only — see [all].
         */
        val ARM32_9_5_0 = LemmingsGrantAbi(
            id = "armeabi-v7a",
            versionNote = "9.5.0 armeabi-v7a (ARM state) — stale, not applied on 9.5.1",
            hookOffset = 0x1131308L,
            hookOrig = "F0412DE908D04DE2",
            hookBranch = "E40700EA", // b #0x11332A0
            targetOffset = 0x11332A0L,
            targetExpected = "F04B2DE910D04DE2",
            popupPrimaryOffset = 0x11CF5CCL,
            popupPrimaryOrig = "10402DE90040A0E10000A0E3",
            popupBackupOffset = 0x11CC680L,
            popupBackupOrig = "F0412DE908D04DE2",
            retStub = "1EFF2FE1", // bx lr
        )

        /** 9.5.1 APKPure XAPK ships `config.arm64_v8a` only. */
        val all = listOf(ARM64)

        /** ABIs whose 9.5.1 offsets are not derived yet — skipped with a warning. */
        val unsupported = listOf("armeabi-v7a")
    }
}

/**
 * Applies the tap-path branch for one ABI: verify the `PurchaseCompleted`
 * prologue (read-only), then swap the `IAPPurchase` head instruction for the
 * relative branch. Throws [PatchException] with NO write if either guard
 * fails — a moved build must fail loudly, never corrupt the lib.
 */
private fun applyGrantHook(lib: File, abi: LemmingsGrantAbi) {
    val tag = "Lemmings native grant hook [${abi.id}]"
    println("$tag: patching ${lib.name} (${lib.length()} bytes; ${abi.versionNote})")

    // Read-only guard on the branch landing site (the actual grant method).
    NativeBytePatcher.verify(
        lib,
        abi.targetOffset,
        abi.targetExpected,
        "PurchaseCompleted prologue (grant target)",
        tag,
        "branch redirect aborted.",
    )

    // Branch redirect at the tap entry.
    if (!NativeBytePatcher.apply(
            lib,
            abi.hookOffset,
            abi.hookOrig,
            abi.hookBranch,
            "StoreElementController::IAPPurchase (tap entry)",
            tag,
        )
    ) {
        throw PatchException(
            "$tag: tap-entry anchor mismatch (file 0x${abi.hookOffset.toString(16)}). " +
                "libil2cpp.so layout changed — unsupported app version?",
        )
    }
}

/**
 * Applies the fail-popup entry→return for one ABI: primary dispatcher first,
 * leaf backup if the primary anchor drifted. Throws [PatchException] (no write)
 * if neither anchor matches — a moved build must fail loudly, never corrupt the
 * lib.
 */
private fun applyPopupAnchor(lib: File, abi: LemmingsGrantAbi) {
    val tag = "Lemmings native popup kill [${abi.id}]"
    println("$tag: patching ${lib.name} (${lib.length()} bytes)")

    if (NativeBytePatcher.apply(
            lib,
            abi.popupPrimaryOffset,
            abi.popupPrimaryOrig,
            abi.retStub,
            "ShowPurchaseFailedPopup dispatcher (A1)",
            tag,
        )
    ) {
        return
    }
    println("$tag: primary anchor mismatch — trying backup leaf site.")
    if (NativeBytePatcher.apply(
            lib,
            abi.popupBackupOffset,
            abi.popupBackupOrig,
            abi.retStub,
            "ShowPurchaseFailedPopup leaf (A2)",
            tag,
        )
    ) {
        return
    }
    throw PatchException(
        "$tag: NEITHER anchor matched " +
            "(A1 file 0x${abi.popupPrimaryOffset.toString(16)}, " +
            "A2 file 0x${abi.popupBackupOffset.toString(16)}). " +
            "libil2cpp.so layout changed — unsupported app version?",
    )
}

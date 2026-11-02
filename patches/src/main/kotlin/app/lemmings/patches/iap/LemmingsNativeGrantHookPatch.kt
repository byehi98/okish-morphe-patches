package app.lemmings.patches.iap

import app.lemmings.patches.shared.Constants.COMPATIBILITY_LEMMINGS
import app.lemmings.patches.shared.NativeBytePatcher
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File

/**
 * Lemmings: Puzzle Survival 9.5.0 · **Native Grant Hook** — store taps grant
 * the tapped item through the game's OWN reward chain, with Unity IAP never
 * entered at all, and the native fail-popup dispatcher is RET-killed (folded
 * inline dependency, single listing entry).
 *
 * **Both shipped ABIs are patched** (`lib/arm64-v8a/` + `lib/armeabi-v7a/`).
 * The 9.5.0 XAPK ships an armv7 split; on an armv7-only device Android executes
 * that lib, so the old arm64-only version of this patch silently did nothing
 * there. The armv7 offsets + evidence (mapped through the IL2CPP
 * method-pointer tables — `global-metadata.dat` is v39, which Il2CppDumper
 * cannot parse) are recorded in `analysis/lemmings/notes/armv7-parity.md`.
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
 * * arm64 file offset **0x1EC7638** (`VA 0x1ECB638`, `VA = file + 0x4000`;
 *   Cpp2IL `AddressAttribute` == file offset directly, NO −0x4000 bias —
 *   re-confirmed in `.../notes/addresses-950.md`; lib md5
 *   `502bd49e80824697bf376a2f0e4c8bb1`, all offsets capstone-disasm-verified).
 *   Entry bytes `FE 5F BD A9` (`stp x30,x23,[sp,#-0x30]!`).
 *   Patch: word0 → `1D 06 00 14` — arm64 `b #0x1EC8EAC`, the entry of
 *   `PurchaseCompleted` (md16190, takes only `this`). Encoding check:
 *   `0x14000000 | ((0x1EC8EAC − 0x1EC7638) >> 2) = 0x14000000 | 0x61D`
 *   — capstone-verified. Relative branch, build-independent.
 * * armv7 file offset **0x01131308** (ARM state, `VA == file offset`, lib md5
 *   `e1fe5b5b8a45c02704dbb5aca6a22116`). Entry bytes
 *   `F0 41 2D E9  08 D0 4D E2` (`push {r4,r5,r6,r7,r8,lr} ; sub sp,#8`) then
 *   `mov r4,r0 ; mov r5,r1` — same 2-arg `(this, bool)` shape as arm64
 *   (`mov x19,x0 ; mov w20,w1`).
 *   Patch: word0 → `E4 07 00 EA` — ARM `b #0x011332A0`, the armv7 entry of
 *   `PurchaseCompleted` (12-byte prologue
 *   `push {r4-r8,sb,fp,lr} ; sub sp,#0x10 ; mov r4,r0` — structurally identical
 *   to arm64 `0x1EC8EAC`). Encoding:
 *   `0xEA000000 | ((0x11332A0 − (0x1131308 + 8)) >> 2) = 0xEA000000 | 0x7E4`.
 * * Both hook into the method BEFORE its own `push`, so on each ABI `r0`/`x0`
 *   is still the controller instance and `lr`/`x30` the caller's return
 *   address; the target builds and pops its own frame. Convention preserved:
 *   the target's internal null-guards always pass on a tappable button, and
 *   the old IAPPurchase body (analytics / mute UI prep) never runs.
 *
 * ============================================================================
 * READ-ONLY GUARD — `PurchaseCompleted` prologue (no write)
 * ============================================================================
 * * arm64: 16 bytes at **0x1EC8EAC** must read
 *   `FF 43 01 D1 FE 13 00 F9 F6 57 03 A9 F4 4F 04 A9`
 *   (`sub sp,sp,#0x50 ; str x30,[sp,#0x20] ; stp x22,x21,… ; stp x20,x19,…`).
 * * armv7: 8 bytes at **0x011332A0** must read
 *   `F0 4B 2D E9  10 D0 4D E2`
 *   (`push {r4-r8,sb,fp,lr} ; sub sp,#0x10`; word 2 is a PC-relative `ldr`
 *   and is deliberately excluded from the guard).
 * * Checked BEFORE the branch write on each ABI: if the grant target drifted
 *   layout, patching the caller blind would jump into garbage — fail loudly
 *   instead.
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
 *   * arm64 file offset **0x1F454AC**, entry `FE 4F BF A9 F3 03 00 AA E0 03 1F AA`
 *     (4th word is a `bl`, excluded). word0 → `C0035FD6` (`ret`).
 *   * armv7 file offset **0x011CF5CC**, entry `10 40 2D E9 00 40 A0 E1 00 00 A0 E3`
 *     (`push {r4,lr} ; mov r4,r0 ; mov r0,#0`; 4th word is a `bl`, excluded).
 *     ARM `B`/`BL` scan: exactly **8** xrefs — matches arm64's "8 UI call
 *     sites converge here". word0 → `1E FF 2F E1` (`bx lr`).
 * * BACKUP: `RockCakes.Purchases.PurchaseFailedPopupController::ShowPurchaseFailedPopup`
 *   — leaf, 1 caller (the dispatcher's tail-call `b`).
 *   * arm64 file offset **0x1F42E28**, 12-B anchor `FE0F1DF8 F65701A9 F44F02A9`
 *     (word3 is an `adrp`, excluded); word0 → `C0035FD6`. 9.4.4 used
 *     `FF0301D1…` there — the leaf prologue changed in 9.5.0.
 *   * armv7 file offset **0x011CC680**, 8-B anchor `F0412DE9 08D04DE2`
 *     (word2 is a PC-relative `ldr`, excluded); word0 → `1E FF 2F E1`.
 *     Scan finds exactly **1** xref (`0x011CF5F4`, the dispatcher's
 *     `b #0x011CC680`) — the same 1-caller relationship as arm64.
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
                if (patchedAbis == 0) {
                    throw PatchException(
                        "Lemmings native popup kill: no libil2cpp.so found in any known ABI " +
                            "split (arm64-v8a, armeabi-v7a) — unexpected split layout?",
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
        if (patchedAbis == 0) {
            throw PatchException(
                "Lemmings native grant hook: no libil2cpp.so found in any known ABI split " +
                    "(arm64-v8a, armeabi-v7a) — unexpected split layout?",
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
        /** 9.5.0 arm64 (md5 502bd49e80824697bf376a2f0e4c8bb1, VA = file + 0x4000). */
        val ARM64 = LemmingsGrantAbi(
            id = "arm64-v8a",
            versionNote = "9.5.0 arm64",
            hookOffset = 0x1EC7638L,
            hookOrig = "FE5FBDA9",
            hookBranch = "1D060014", // b #0x1EC8EAC
            targetOffset = 0x1EC8EACL,
            targetExpected = "FF4301D1FE1300F9F65703A9F44F04A9",
            popupPrimaryOffset = 0x1F454ACL,
            popupPrimaryOrig = "FE4FBFA9F30300AAE0031FAA",
            popupBackupOffset = 0x1F42E28L,
            popupBackupOrig = "FE0F1DF8F65701A9F44F02A9",
            retStub = "C0035FD6", // ret
        )

        /**
         * 9.5.0 armeabi-v7a (md5 e1fe5b5b8a45c02704dbb5aca6a22116, VA == file
         * offset). ARM state (A32), not Thumb: every prologue starts `E92D…`.
         */
        val ARM32 = LemmingsGrantAbi(
            id = "armeabi-v7a",
            versionNote = "9.5.0 armeabi-v7a (ARM state)",
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

        val all = listOf(ARM64, ARM32)
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

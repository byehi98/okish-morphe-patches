package app.lemmings.patches.iap

import app.lemmings.patches.shared.Constants.COMPATIBILITY_LEMMINGS
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile

/**
 * Lemmings: Puzzle Survival 9.5.0 · **Native Grant Hook** — store taps grant
 * the tapped item through the game's OWN reward chain, with Unity IAP never
 * entered at all, and the native fail-popup dispatcher is RET-killed (folded
 * inline dependency, single listing entry).
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
 *              → ShowRewardReceipt (0x1F45084 / 0x1F3F21C)
 *              → ApplyReward (0x1F454D0)
 *              → RewardHandlerCurrency::HandleReward (0x1F1EC04) → GRANT
 * ```
 *
 * ============================================================================
 * TARGET — `RockCakes.Store.StoreElementController::IAPPurchase` (md16158)
 * ============================================================================
 * * The EXACT hook the real-money store button invokes on tap (2-arg
 *   overload `(this, bool)` — w1 is unused by the hooked target).
 * * file offset **0x1EC7638** (Cpp2IL 9.5.0 `AddressAttribute` value == file
 *   offset directly, NO −0x4000 bias — re-confirmed in
 *   `analysis/com.sadpuppy.lemmings/notes/addresses-950.md`; lib md5
 *   `502bd49e80824697bf376a2f0e4c8bb1`, all offsets capstone-disasm-verified
 *   from the real .so).
 * * Entry bytes (shipped lib, word0 verified):
 *   `FE 5F BD A9` (`stp x30,x23,[sp,#-0x30]!`).
 * * Patch: word0 → `1D 06 00 14` — arm64 `b #0x1EC8EAC`, the entry of
 *   `StoreElementController::PurchaseCompleted` (md16190, takes only `this`).
 *   Encoding check: `0x14000000 | ((0x1EC8EAC − 0x1EC7638) >> 2)
 *   = 0x14000000 | 0x61D = 0x1400061D` — capstone-verified to decode
 *   `b #0x1ec8eac` from 0x1EC7638. Relative branch, no absolute address,
 *   build-independent.
 * * Convention preserved: `bl` callers assumed, `x0` = controller instance,
 *   `PurchaseCompleted` pushes its own frame, its internal null-guards
 *   (`cbz → 0x1EC90E8` on the group/element handles) always pass on a
 *   tappable button. The old IAPPurchase body (analytics / mute UI prep)
 *   never runs — none of it is required for the grant.
 *
 * ============================================================================
 * READ-ONLY GUARD — `PurchaseCompleted` prologue (no write)
 * ============================================================================
 * 16 bytes at **0x1EC8EAC** must read
 * `FF 43 01 D1 FE 13 00 F9 F6 57 03 A9 F4 4F 04 A9`
 * (`stp x30,x22,[sp,#-0x20]! ; str x23,[sp,#8] ; stp x21,x20,…`). Checked
 * BEFORE the branch write: if the grant target drifted layout, patching the
 * caller blind would jump into garbage — fail loudly instead.
 *
 * ============================================================================
 * FOLDED DEPENDENCY — native fail-popup RET (popup-kill.md TARGET A/B)
 * ============================================================================
 * The `RockCakes.Purchases` fail-popup dispatcher and its leaf popup get the
 * same word0→RET treatment via an inline `dependsOn(rawResourcePatch{…})`
 * (CrossyRoad/DeadTrigger shape) so grant hook + popup kill ship as ONE
 * listing entry ("Lemmings: Native Grant Hook") and the patcher applies the
 * native edit before this patch. Full evidence:
 * `analysis/com.sadpuppy.lemmings/notes/popup-kill.md`.
 *
 * * PRIMARY: `RockCakes.Purchases.PurchaseReceiptManager::ShowPurchaseFailedPopup`
 *   — the CENTRAL fail-popup dispatcher (8 UI call sites converge here). File
 *   offset **0x1F454AC**, entry `FE 4F BF A9 F3 03 00 AA E0 03 1F AA`
 *   (4th word is a `bl`, relocation-sensitive — excluded from the 12-B guard).
 *   word0 → `C0035FD6` (`ret`): `FireFailedPurchase` + leaf popup never run.
 * * BACKUP: `RockCakes.Purchases.PurchaseFailedPopupController::ShowPurchaseFailedPopup`
 *   — leaf, 1 caller (the dispatcher's tail-call `b`). File offset
 *   **0x1F42E28**, 12-B anchor `FE0F1DF8 F65701A9 F44F02A9` (word3 is an
 *   `adrp`, excluded); word0 → `C0035FD6`. 9.4.4 used `FF0301D1…` there —
 *   the leaf prologue changed in 9.5.0.
 * * Neither anchor matches → loud [PatchException], never a blind write.
 * * armv7: NO hunter-verified anchor (Thumb-2 needs its own analysis) — left
 *   byte-identical (logged, not fatal; arm64 covers all modern devices).
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
 * extraction so both `get(…)` calls resolve. Application-time logging follows
 * the established "Lemmings …" println style ("MorphLem"-tagged runtime logs
 * are deliberately not needed here).
 *
 * Device test: open the store, tap a real-money item (e.g. `gold12k`) →
 * the game's reward claim popup (NOT Play billing) + currency/egg grant in
 * the HUD; logcat shows NO `FireFailedPurchase` / `ProcessPurchase`, and a
 * failed checkout (if ever reached) shows no fail popup.
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
                val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
                if (!arm64.exists()) {
                    throw PatchException(
                        "Lemmings native popup kill: lib/arm64-v8a/libil2cpp.so absent — " +
                            "unexpected split layout?",
                    )
                }
                applyPopupAnchor(arm64)

                // armv7: NO hunter-verified anchor (Thumb-2 needs its own
                // analysis) — leave byte-identical, say so loudly. Only the
                // fail popup pair would ever show there.
                val armv7 = get("lib/armeabi-v7a/libil2cpp.so", true)
                if (armv7.exists()) {
                    println(
                        "Lemmings native popup kill: lib/armeabi-v7a/libil2cpp.so present " +
                            "but no verified armv7 anchor — leaving untouched " +
                            "(arm64 patched).",
                    )
                }
            }
        },
    )

    execute {
        val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
        if (!arm64.exists()) {
            throw PatchException(
                "Lemmings native grant hook: lib/arm64-v8a/libil2cpp.so absent — " +
                    "unexpected split layout?",
            )
        }
        applyGrantHook(arm64)

        // armv7: NO hunter-verified anchor (Thumb-2 needs its own
        // analysis) — leave byte-identical, say so loudly.
        val armv7 = get("lib/armeabi-v7a/libil2cpp.so", true)
        if (armv7.exists()) {
            println(
                "Lemmings native grant hook: lib/armeabi-v7a/libil2cpp.so present " +
                    "but no verified armv7 anchor — leaving untouched " +
                    "(arm64 patched).",
            )
        }
    }
}

/** Tap-path hijack site (`StoreElementController::IAPPurchase`, word0 STP prologue). */
private const val GRANT_HOOK_OFFSET = 0x1EC7638L
private const val GRANT_HOOK_ORIG = "FE5FBDA9"

/** arm64 `b #0x1EC8EAC` (→ `PurchaseCompleted`); see KDoc encoding check. */
private const val GRANT_HOOK_BRANCH = "1D060014"

/** Read-only preflight on the grant target (`PurchaseCompleted` 16-B prologue). */
private const val GRANT_TARGET_OFFSET = 0x1EC8EACL
private const val GRANT_TARGET_EXPECTED = "FF4301D1FE1300F9F65703A9F44F04A9"

/** PRIMARY fail-popup kill site (TARGET A1 — dispatcher → RET at entry). */
private const val POPUP_PRIMARY_OFFSET = 0x1F454ACL
private const val POPUP_PRIMARY_ORIG = "FE4FBFA9F30300AAE0031FAA"
private const val POPUP_RET = "C0035FD6"

/** BACKUP fail-popup kill site (TARGET A2 — leaf popup → RET at entry). */
private const val POPUP_BACKUP_OFFSET = 0x1F42E28L
private const val POPUP_BACKUP_ORIG = "FE0F1DF8F65701A9F44F02A9"

/**
 * Applies the tap-path branch: verify the `PurchaseCompleted` prologue
 * (read-only), then swap the `IAPPurchase` head instruction for the relative
 * branch. Throws [PatchException] with NO write if either guard fails — a
 * moved build must fail loudly, never corrupt the lib.
 */
private fun applyGrantHook(lib: File) {
    println("Lemmings native grant hook: patching ${lib.name} (${lib.length()} bytes)")

    // Read-only guard on the branch landing site (the actual grant method).
    grantCheckBytes(lib, GRANT_TARGET_OFFSET, GRANT_TARGET_EXPECTED, "PurchaseCompleted prologue (grant target)")

    // 4-byte branch redirect at the tap entry.
    if (!grantWriteBytes(lib, GRANT_HOOK_OFFSET, GRANT_HOOK_ORIG, GRANT_HOOK_BRANCH, "StoreElementController::IAPPurchase (tap entry)")) {
        throw PatchException(
            "Lemmings native grant hook: tap-entry anchor mismatch " +
                "(file 0x${GRANT_HOOK_OFFSET.toString(16)}). " +
                "libil2cpp.so layout changed — unsupported app version?",
        )
    }
}

/**
 * Applies the fail-popup RET: primary dispatcher first, leaf backup if the
 * primary anchor drifted. Throws [PatchException] (no write) if neither
 * anchor matches — a moved build must fail loudly, never corrupt the lib.
 */
private fun applyPopupAnchor(lib: File) {
    println("Lemmings native popup kill: patching ${lib.name} (${lib.length()} bytes)")
    if (popupTryAnchor(lib, POPUP_PRIMARY_OFFSET, POPUP_PRIMARY_ORIG, "ShowPurchaseFailedPopup dispatcher (A1)")) return
    println(
        "Lemmings native popup kill: primary anchor mismatch — " +
            "trying backup leaf site.",
    )
    if (popupTryAnchor(lib, POPUP_BACKUP_OFFSET, POPUP_BACKUP_ORIG, "ShowPurchaseFailedPopup leaf (A2)")) return
    throw PatchException(
        "Lemmings native popup kill: NEITHER anchor matched " +
            "(A1 file 0x${POPUP_PRIMARY_OFFSET.toString(16)}, " +
            "A2 file 0x${POPUP_BACKUP_OFFSET.toString(16)}). " +
            "libil2cpp.so layout changed — unsupported app version?",
    )
}

/** Verifies bytes at [offset] match [expectedHex] (READ ONLY). Throws on drift. */
private fun grantCheckBytes(lib: File, offset: Long, expectedHex: String, label: String) {
    RandomAccessFile(lib, "r").use { raf ->
        if (raf.length() < offset + expectedHex.length / 2) {
            throw PatchException(
                "Lemmings native grant hook: $label short lib — " +
                    "file 0x${offset.toString(16)} out of range.",
            )
        }
        raf.seek(offset)
        val actual = ByteArray(expectedHex.length / 2)
        raf.readFully(actual)
        if (!actual.contentEquals(grantHexBytes(expectedHex))) {
            throw PatchException(
                "Lemmings native grant hook: $label mismatch at file " +
                    "0x${offset.toString(16)} (found ${grantToHex(actual)}, " +
                    "expected $expectedHex) — branch redirect aborted, lib " +
                    "left unmodified.",
            )
        }
        println(
            "Lemmings native grant hook: $label verified at file " +
                "0x${offset.toString(16)} (read-only guard).",
        )
    }
}

/** Reads anchor bytes, compares, writes [replacementHex] in place. True if applied. */
private fun grantWriteBytes(
    lib: File,
    offset: Long,
    anchorHex: String,
    replacementHex: String,
    label: String,
): Boolean {
    RandomAccessFile(lib, "rw").use { raf ->
        if (raf.length() < offset + anchorHex.length / 2) return false
        raf.seek(offset)
        val actual = ByteArray(anchorHex.length / 2)
        raf.readFully(actual)
        if (!actual.contentEquals(grantHexBytes(anchorHex))) {
            println(
                "Lemmings native grant hook: $label anchor mismatch at file " +
                    "0x${offset.toString(16)} (found ${grantToHex(actual)}).",
            )
            return false
        }
        raf.seek(offset)
        raf.write(grantHexBytes(replacementHex))
        println(
            "Lemmings native grant hook: $label file 0x${offset.toString(16)}: " +
                "$anchorHex -> $replacementHex " +
                "(b StoreElementController::PurchaseCompleted at 0x1ec8eac)",
        )
        return true
    }
}

/** Reads the popup anchor, compares, writes RET in place. Returns true if applied. */
private fun popupTryAnchor(lib: File, offset: Long, originalHex: String, label: String): Boolean {
    RandomAccessFile(lib, "rw").use { raf ->
        if (raf.length() < offset + 4) return false
        raf.seek(offset)
        val anchorLen = originalHex.length / 2
        val actual = ByteArray(anchorLen)
        raf.readFully(actual)
        if (!actual.contentEquals(grantHexBytes(originalHex))) {
            println(
                "Lemmings native popup kill: $label anchor mismatch at file " +
                    "0x${offset.toString(16)} (found ${grantToHex(actual)}).",
            )
            return false
        }
        raf.seek(offset)
        raf.write(grantHexBytes(POPUP_RET))
        println(
            "Lemmings native popup kill: $label file 0x${offset.toString(16)}: " +
                "${originalHex.substring(0, 8)} -> $POPUP_RET (ret)",
        )
        return true
    }
}

/** Parses a plain hex string (no separators) into bytes. */
private fun grantHexBytes(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun grantToHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

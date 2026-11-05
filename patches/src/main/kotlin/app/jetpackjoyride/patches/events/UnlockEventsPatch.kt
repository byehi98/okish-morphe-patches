package app.jetpackjoyride.patches.events

import app.jetpackjoyride.patches.shared.AbiEdits
import app.jetpackjoyride.patches.shared.Constants.COMPATIBILITY_JETPACKJOYRIDE
import app.jetpackjoyride.patches.shared.NativeAnchor
import app.jetpackjoyride.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.rawResourcePatch

private const val PATCH_NAME = "Unlock Events"

/** Halfbrick's "Mortar" engine — carries all gameplay/monetisation logic. */
private const val MORTAR_LIB = "libmortargame.so"

/**
 * Jetpack Joyride — Unlock Events (bypass the "IAP cheat" event lock)
 * (com.halfbrick.jetpackjoyride v1.104.2 / versionCode 891646000)
 *
 * Events such as the seasonal worlds are only playable when the game does **not**
 * consider the account an in-app-purchase cheater. The free-IAP patch forges a
 * purchase locally, so the receipt the game forwards for validation is rejected,
 * the shipped `PurchaseValidator` bumps `monetisation_status.invalid`, and from then
 * on `MonetisationStatusService::GetIapStatus()` answers `"cheat"`. Every event whose
 * config carries `"restrict_cheaters_access": true` (17 of the 18 shipped event
 * configs, and the live BrickNet variants of them) then greys out and, on tap, only
 * ever shows the **EventRestrictedPopUp**.
 *
 * The whole chain is native C++ in `libmortargame.so`; there is no smali to patch.
 *
 * ```
 * EventSystem::ActivateEvent(EventDetails*, AsciiString const&, bool, EventDate*)
 *   ldrb   w8, [event, #0x3a0]          ; restrict_cheaters_access (set by
 *                                       ; EventDetails::Deserialise from the JSON)
 *   cbz    w8, normal_path              ; flag off -> event always playable
 *   bl     JetpackUser::GetMonetisationStatus()   ; 0 = non-pay, 2 = paying, 3 = cheat
 *   cmp    w0, #3
 *   b.ne   normal_path
 *   bl     TitleMenu::ScheduleEventRestrictedPopup(event)   ; <- the popup
 *   return false                                          ; <- the event never starts
 * ```
 *
 * `JetpackUser::GetMonetisationStatus()` returns `3` from exactly two places:
 * the developer-menu "force cheater" toggle (unreachable in a release build) and
 * `MonetisationStatusService::IsIapCheat()`:
 *
 * ```
 * Jetpack::MonetisationStatusService::IsIapCheat(Mortar::IUser*) const
 *   ldr  x8, [this]                 ; vtable
 *   ldr  x8, [x8, #0x98]            ; GetIapStatus(IUser*)
 *   blr  x8                         ; "pay" / "nonpay" / "cheat" (from save data:
 *                                   ;   valid>0 -> "pay"; bricknet iap status;
 *                                   ;   invalid>0 -> "cheat")
 *   adrp x1, "cheat"
 *   bl   OS_strcmp
 *   cmp  w0, #0
 *   cset w0, eq                     ; return (status == "cheat")
 * ```
 *
 * This patch replaces that function's entry with an immediate `return false`
 * (`mov w0, wzr ; ret`) on every shipped ABI. `GetMonetisationStatus()` then
 * reports `0` (non-pay, i.e. an ordinary free player) instead of `3`, so
 * `EventSystem::ActivateEvent` always takes its normal path: the event starts and
 * `TitleMenu::ScheduleEventRestrictedPopup` is never reached, so
 * `TitleMenu::m_restrictedEvent` stays null and `TitleMenu::SchedulePopups()`
 * never has an EventRestrictedPopUp to show.
 *
 * ## Why this is safe
 *
 *  * `IsIapCheat` has **no direct callers**: a whole-lib scan finds no `bl` to its
 *    symbol and no PLT entry for it — it is only ever dispatched through the
 *    `IMonetizationStatusService` vtable (`+0xb0`), so patching the body cannot
 *    desynchronise an inlined copy.
 *  * Its only consumers are `JetpackUser::GetMonetisationStatus()` (event gate +
 *    support-dashboard metadata string) — nothing grants currency, unlocks items or
 *    reads a return-size side effect from it.
 *  * The patch is a same-length, in-place body rewrite: the shipped prologue is
 *    replaced by an early return, the rest of the function stays as unreachable
 *    dead code and the lib length never changes.
 *  * It is independent of the DEX patches (billing / ads): no `dependsOn`, no
 *    ordering constraint, can be applied alone.
 *
 * ## What is NOT touched
 *
 *  * `MonetisationStatusService::GetIapStatus()` still returns `"cheat"` from save
 *    data — this patch only stops that string from gating gameplay. Receipt
 *    validation, analytics and the "pay"/"nonpay" distinction are untouched, so a
 *    genuinely paying account still reports as paying.
 *  * `restrict_cheaters_access` is read from the live event configs; those files
 *    ship inside `split_InstallPack.apk` and are replaced by BrickNet responses at
 *    runtime, so patching the JSON would be both fragile and ineffective. The gate
 *    itself is the correct choke point.
 *  * Leaderboards, tickets and event missions are untouched — they read their own
 *    data sources.
 *
 * ## Byte sites (per ABI, measured against that ABI's own shipped lib)
 *
 * All four libs map `p_vaddr == p_offset == 0` for the RX `PT_LOAD`
 * (`readelf -lW`), so file offset == RVA in every case. Each 24-byte verify window
 * was measured whole-file-unique (1 occurrence) at patch time.
 *
 * | ABI         | `IsIapCheat` RVA | replacement                                  |
 * |-------------|------------------|----------------------------------------------|
 * | arm64-v8a   | 0x12A9098        | `mov w0, wzr ; ret`   = e0 03 1f 2a c0 03 5f d6 |
 * | armeabi-v7a | 0xE9E6A0 (Thumb) | `movs r0,#0 ; bx lr`  = 00 20 70 47           |
 * | x86         | 0x1323320        | `xor eax,eax ; ret ; nop` = 31 c0 c3 90       |
 * | x86_64      | 0x1366140        | `xor eax,eax ; ret ; nop` = 31 c0 c3 90       |
 *
 * The arm64 and armeabi-v7a encodings came from `llvm-mc` (AArch64 / Thumb-2) and
 * were decoded back with `llvm-objdump` at the shipped offsets; x86/x86_64 were
 * verified the same way. Every anchor's window is unique in its lib, so a moved or
 * changed build aborts the patch with zero bytes written.
 */
@Suppress("unused")
val jetpackJoyrideUnlockEventsPatch = rawResourcePatch(
    name = PATCH_NAME,
    description = "Unlocks every event (seasonal worlds, event missions and their " +
        "pop-ups) by disabling the game's internal \"IAP cheat\" flag check. When a " +
        "free-IAP patch forges a purchase the game's receipt validator marks the " +
        "account as a cheater and locks all cheater-restricted events behind an " +
        "'Event Restricted' pop-up; this patch makes that check always answer " +
        "'not a cheat', so the events open normally.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JETPACKJOYRIDE)

    execute {
        applyNativeEdits(
            patchName = PATCH_NAME,
            libName = MORTAR_LIB,
            targets = listOf(
                AbiEdits("arm64-v8a", listOf(ARM64_IS_IAP_CHEAT)),
                AbiEdits("armeabi-v7a", listOf(V7A_IS_IAP_CHEAT)),
                AbiEdits("x86", listOf(X86_IS_IAP_CHEAT)),
                AbiEdits("x86_64", listOf(X86_64_IS_IAP_CHEAT)),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Native anchors — one per shipped ABI of libmortargame.so 1.104.2.
//
// The replacement bytes are identical in shape on every ABI ("return false"), but
// the instruction encodings, offsets and surrounding bytes are per-ABI and are
// never shared or extrapolated.
// ---------------------------------------------------------------------------

private val ARM64_IS_IAP_CHEAT = NativeAnchor(
    label = "MonetisationStatusService::IsIapCheat → return false (arm64-v8a: mov w0,wzr; ret)",
    rva = 0x12A9098,
    fileOffset = 0x12A9098,
    verifyHex = "fd7bbfa9fd030091080040f9084d40f900013fd6c1b0ffb0",
    replacementHex = "e0031f2ac0035fd6",
)

private val V7A_IS_IAP_CHEAT = NativeAnchor(
    label = "MonetisationStatusService::IsIapCheat → return false (armeabi-v7a Thumb: movs r0,#0; bx lr)",
    rva = 0xE9E6A0,
    fileOffset = 0xE9E6A0,
    verifyHex = "80b56f460268d26c904704497944b8f148e0b0fa80f04009",
    replacementHex = "00207047",
)

private val X86_IS_IAP_CHEAT = NativeAnchor(
    label = "MonetisationStatusService::IsIapCheat → return false (x86: xor eax,eax; ret; nop)",
    rva = 0x1323320,
    fileOffset = 0x1323320,
    verifyHex = "5383ec08e8000000005b81c323c2b3008b4424108b0883ec",
    replacementHex = "31c0c390",
)

private val X86_64_IS_IAP_CHEAT = NativeAnchor(
    label = "MonetisationStatusService::IsIapCheat → return false (x86_64: xor eax,eax; ret; nop)",
    rva = 0x1366140,
    fileOffset = 0x1366140,
    verifyHex = "50488b07ff9098000000488d35f07255ff4889c7e8f78ba1",
    replacementHex = "31c0c390",
)

package app.magicsort.ads

import app.magicsort.shared.AbiAnchorSet
import app.magicsort.shared.Constants.COMPATIBILITY_MAGIC_SORT
import app.magicsort.shared.NativeAnchor
import app.magicsort.shared.applyLibil2cppEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Magic Sort 0.0.3480 — **Remove Ads** (static native patch, both ABIs).
 *
 * Unity IL2CPP title: everything that decides whether an ad may be shown is C#
 * compiled into `lib/<abi>/libil2cpp.so` (see analysis/magicsort/). There is no
 * ad-blocking DEX hook that can reach it.
 *
 * The single edit per ABI is the app's own local NoAds entitlement getter.
 *
 * arm64-v8a @ `0x2B402DC` (95,838,880 B lib):
 * ```
 * str  x30, [sp, #-0x10]!
 * ldr  x8, [x0, #0x18]      ; this->_model
 * cbz  x8, <null path>      ; null -> NullReferenceException
 * ldrb w0, [x8, #0x18]      ; model._isAdsDisabled
 * ldr  x30, [sp], #0x10
 * ret
 * ```
 * Replacement `mov w0, #1 ; ret` (`20 00 80 52 C0 03 5F D6`).
 *
 * armeabi-v7a @ `0x28E8C04` (86,265,520 B lib, ARM/A32 mode):
 * ```
 * push {r4, lr}
 * ldr  r4, [r0, #0xc]       ; this->_model
 * cmp  r4, #0
 * beq  <null path>
 * ldrb r0, [r4, #0x10]      ; model._isAdsDisabled
 * pop  {r4, pc}
 * ```
 * Replacement `mov r0, #1 ; bx lr` (`01 00 A0 E3 1E FF 2F E1`).
 *
 * The 8-byte replacement short-circuits the whole body, so the property reads `true`
 * on every call. It is the *only* ad gate that matters, because the getter has
 * exactly three consumers and all of them disable ad delivery:
 *
 * | Consumer | Effect |
 * |---|---|
 * | `AdService.CanShowInterstitial` | every interstitial path funnels here (`CanShowLaterInterstitial`, `CanShowEarlyInterstitial`, in-level/play-button/daily call sites), so banners, interstitials and app-open ads never show |
 * | `AdService.InitializeAds` | the interstitial adapter is never even constructed — the SDK stays uninitialised, which also removes the network/telemetry traffic |
 * | `NoAdsOfferStateService.GetState` | the store hides the "remove ads" offer, because the entitlement now reads as owned |
 *
 * Why patching the **getter** (and not the model byte / save file) is the durable
 * fix: `InAppPurchaseHistoryService` is `ISyncable` and the GrandSDK backend can
 * call `OverwriteModelFromSync` to rewrite the model, but it cannot rewrite a
 * patched getter — the server never sees `HasNoAds`, it only sends data. The NoAds
 * entitlement is local (`InAppPurchaseHistoryModel._isAdsDisabled`), not
 * server-authoritative, so there is nothing that re-validates or revokes it.
 *
 * Only 8 bytes are written per ABI and the replacement is smaller than the
 * uniquely-verified window, so no other function can be touched (see
 * [app.magicsort.shared.applyLibil2cppEdits]). The ad SDK jars, the mediation dex
 * files under `assets/` and the SDK libs under `lib/` are deliberately left in
 * place: the app still links them, and removing them is riskier than never
 * initialising them.
 */
@Suppress("unused")
val removeAdsPatch = rawResourcePatch(
    name = "Remove Ads",
    description = "All ads are permanently removed — banners, interstitials and app-open ads never load, and the store treats the no-ads product as owned.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAGIC_SORT)

    execute {
        applyLibil2cppEdits(
            patchName = "Remove Ads",
            anchorSets = listOf(
                AbiAnchorSet("arm64-v8a", listOf(HAS_NO_ADS_ARM64)),
                AbiAnchorSet("armeabi-v7a", listOf(HAS_NO_ADS_V7A)),
            ),
        )
    }
}

/**
 * `InAppPurchaseHistoryService.HasNoAds()` → `true` (arm64-v8a, 0.0.3480).
 *
 * Original bytes (28 B, exactly one hit in 95,838,880 B):
 * `fe 0f 1f f8` = `str x30,[sp,#-0x10]!`, `08 0c 40 f9` = `ldr x8,[x0,#0x18]`,
 * `88 00 00 b4` = `cbz x8,<null path>`, `00 61 40 39` = `ldrb w0,[x8,#0x18]`,
 * `fe 07 41 f8` = `ldr x30,[sp],#0x10`, `c0 03 5f d6` = `ret`.
 * The next function's `bl` (`0d 67 9a 97`) extends the window to 28 B, which keeps
 * it unique (the 24 B prefix alone is not guaranteed to be).
 */
private val HAS_NO_ADS_ARM64 = NativeAnchor(
    label = "InAppPurchaseHistoryService.HasNoAds -> true (banners + interstitials + no-ads offer)",
    rva = 0x2B402DC,
    fileOffset = 0x2B402DC,
    verifyHex = "fe0f1ff8080c40f9880000b400614039fe0741f8c0035fd60d679a97",
    replacementHex = "20008052c0035fd6",
)

/**
 * `InAppPurchaseHistoryService.HasNoAds()` → `true` (armeabi-v7a, 0.0.3480).
 *
 * Original bytes (40 B, exactly one hit in 86,265,520 B — the 24 B form of the
 * getter body occurs **three** times in the 32-bit lib, so the window is extended
 * over the `NullReferenceException` path (`bl` + fall-through `ldrb`/`pop`) and the
 * next function's `push {r4,r5,r6,r7,r8,lr}` prologue):
 * `10 40 2d e9` = `push {r4,lr}`, `0c 40 90 e5` = `ldr r4,[r0,#0xc]`,
 * `00 00 54 e3` = `cmp r4,#0`, `01 00 00 0a` = `beq <null path>`,
 * `10 00 d4 e5` = `ldrb r0,[r4,#0x10]`, `10 80 bd e8` = `pop {r4,pc}`,
 * then the null path and the next prologue.
 */
private val HAS_NO_ADS_V7A = NativeAnchor(
    label = "InAppPurchaseHistoryService.HasNoAds -> true (banners + interstitials + no-ads offer)",
    rva = 0x28E8C04,
    fileOffset = 0x28E8C04,
    verifyHex = "10402de90c4090e5000054e30100000a1000d4e51080bde8455484eb1000d4e51080bde8f0412de9",
    replacementHex = "0100a0e31eff2fe1",
)

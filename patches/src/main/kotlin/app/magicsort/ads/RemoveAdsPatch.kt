package app.magicsort.ads

import app.magicsort.shared.Constants.COMPATIBILITY_MAGIC_SORT
import app.magicsort.shared.NativeAnchor
import app.magicsort.shared.applyLibil2cppEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Magic Sort 0.0.3420 — **Remove Ads** (static native patch).
 *
 * Unity IL2CPP title: everything that decides whether an ad may be shown is C#
 * compiled into `lib/arm64-v8a/libil2cpp.so` (see
 * analysis/magicsort/possible-patches.md §1–§3). There is no ad-blocking DEX hook
 * that can reach it.
 *
 * The single edit is the app's own local NoAds entitlement getter:
 *
 * ```
 * 0x2B054F8  InAppPurchaseHistoryService.HasNoAds()
 *            str  x30, [sp, #-0x10]!
 *            ldr  x8, [x0, #0x18]      ; this->_model
 *            cbz  x8, #0x2B05510       ; null -> NullReferenceException
 *            ldrb w0, [x8, #0x18]      ; model._isAdsDisabled
 *            ret
 * ```
 *
 * The 8-byte replacement `mov w0, #1 ; ret`
 * (`20 00 80 52 C0 03 5F D6`) short-circuits the whole body, so the property reads
 * `true` on every call. It is the *only* ad gate that matters, because the getter
 * has exactly three consumers and all of them disable ad delivery:
 *
 * | Consumer | Call site | Effect |
 * |---|---|---|
 * | `AdService.CanShowInterstitial` (`0x1B5EF0C`) | `BL` at `0x1B5EFF4` | `tbnz w0,#0` → `return false`; every interstitial path funnels here (`CanShowLaterInterstitial`, `CanShowEarlyInterstitial`, in-level/play-button/daily call sites), so banners, interstitials and app-open ads never show |
 * | `AdService.InitializeAds` (`0x1B5EA0C`) | `BL` at `0x1B5EAD0` | the interstitial adapter is never even constructed — the SDK stays uninitialised, which also removes the network/telemetry traffic |
 * | `NoAdsOfferStateService.GetState` (`0x3464E4C`) | `0x3464E80` | the store hides the "remove ads" offer, because the entitlement now reads as owned |
 *
 * Why patching the **getter** (and not the model byte / save file) is the durable
 * fix: `InAppPurchaseHistoryService` is `ISyncable` and the GrandSDK backend can
 * call `OverwriteModelFromSync` (`0x2B06660`) to rewrite the model, but it cannot
 * rewrite a patched getter — the server never sees `HasNoAds`, it only sends data.
 * The NoAds entitlement is local (`InAppPurchaseHistoryModel._isAdsDisabled`), not
 * server-authoritative, so there is nothing that re-validates or revokes it.
 *
 * Only 8 bytes are written and the replacement is smaller than the 28-byte
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
            anchors = listOf(HAS_NO_ADS),
        )
    }
}

/**
 * `InAppPurchaseHistoryService.HasNoAds()` → `true`.
 *
 * Original bytes (28 B, measured on the 0.0.3420 lib, exactly one hit in 95,548,936 B):
 * `fe 0f 1f f8` = `str x30,[sp,#-0x10]!`, `08 0c 40 f9` = `ldr x8,[x0,#0x18]`,
 * `88 00 00 b4` = `cbz x8,#0x2B05510`, `00 61 40 39` = `ldrb w0,[x8,#0x18]`,
 * `fe 07 41 f8` = `ldr x30,[sp],#0x10`, `c0 03 5f d6` = `ret`.
 * Then the next function's prologue (`78 2e 9b 97`) extends the window to 28 B.
 */
private val HAS_NO_ADS = NativeAnchor(
    label = "InAppPurchaseHistoryService.HasNoAds -> true (banners + interstitials + no-ads offer)",
    rva = 0x2B054F8,
    fileOffset = 0x2B054F8,
    verifyHex = "fe0f1ff8080c40f9880000b400614039fe0741f8c0035fd6782e9b97",
    replacementHex = "20008052c0035fd6",
)

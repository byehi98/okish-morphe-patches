package app.ninjaarashi2.patches.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.ninjaarashi2.patches.shared.AbiEdits
import app.ninjaarashi2.patches.shared.Constants.COMPATIBILITY_NINJA_ARASHI_2
import app.ninjaarashi2.patches.shared.NativeAnchor
import app.ninjaarashi2.patches.shared.applyNativeEdits

/**
 * Ninja Arashi 2 1.9.6 · **Free Ad Rewards** — every "watch an ad" button pays out
 * immediately, without an ad and without a network connection.
 *
 * Unity 6000.2.6f2 / IL2CPP: both reward flows are `Assembly-CSharp` compiled into
 * `libil2cpp.so`. `AdCreator.ShowFullAD` / `CompleteMethod` are delegate targets with **zero
 * direct `bl` callers**, so their bodies have to be edited; the two `MainCanvas.Update` gates
 * additionally decide whether the gift button is even drawn as available.
 *
 * ============================================================================
 * FLOW 1 — main-menu gift (100 diamonds)
 * ============================================================================
 * ```
 * MainCanvas.Update
 *   adhas = PlayerPrefs.GetInt(...)              <-- EDIT A: drop the "adhas >= 1" gate
 *   if (adhas < 1) skip the button
 *   if (!MobileAdsManager.IsRewardedVideoAvailable()) skip   <-- EDIT B: drop this gate
 *   button.onClick -> AdCreator.ShowFullAD(0)
 *       this.lv = 0
 *       if (!IsRewardedVideoAvailable()) return              <-- EDIT C: jump to the grant
 *       API.ShowRewardedVideo(CompleteMethod)
 * ```
 * With the two gates `nop`ed the button is present and clickable offline; EDIT C then makes
 * the click run `CompleteMethod(this, true)` immediately, which is exactly what the SDK
 * callback would have done: `MainCanvas.AddDmByWatchAd(100)`.
 *
 * ```
 * arm64 0x1523134 (file 0x151F134): mov x0, x19 ; mov w1, #1 ; bl CompleteMethod ; b <epilogue>
 * v7a   0xAF5D14  (file 0xAF5D14) : mov r0, r4  ; mov r1, #1 ; bl CompleteMethod ; pop {r4,r5,r11,pc}
 * ```
 * `x19` / `r4` hold `this` at that point (`this.lv` was already stored just above), and
 * `CompleteMethod` writes back into `this` (it clears `GiftAdReady` at `this+0x20`), so the
 * same object **must** be passed — a fresh one would corrupt the ad state. The frame is
 * unwound properly: arm64 branches to the function's own epilogue, v7a pops the exact
 * register set the prologue pushed. A bare tail branch with the frame still allocated would
 * return to a misaligned stack (the v1 crash).
 *
 * EDIT D — `AdCreator.CheckAd` is forced to report "ready" for any other UI that reads it:
 * ```
 * arm64 0x152319C (file 0x151F19C): mov w8, #1   (was: and w8, w0, #1)
 * v7a   0xAF5D80  (file 0xAF5D80) : mov r0, #1 ; strb r0, [r4, #0x10]
 * ```
 * Both leave the following store (`strb w8, [x19, #0x20]` / the original epilogue) intact, so
 * the flag lands exactly as it would after a successful availability probe.
 *
 * ============================================================================
 * FLOW 2 — in-level revive
 * ============================================================================
 * ```
 * MainEventsLog.liveAds()
 *   if (!IsRewardedVideoAvailable()) return
 *   ShowRewardedVideo(...)
 * ```
 * The body is rewritten to `mov w1, #1 ; b MainEventsLog.XemAdHoiSinh` — the same completion
 * callback the SDK would have invoked — so the revive canvas revives you with no ad:
 * ```
 * arm64 0x15B9C1C (file 0x15B5C1C): mov w1, #1 ; b 0x15B9CD0
 * v7a   0xBC9C50  (file 0xBC9C50) : mov r1, #1 ; b 0xBC9D2C
 * ```
 *
 * ============================================================================
 * WHAT IS DELIBERATELY NOT PATCHED
 * ============================================================================
 * `MobileAdsManager.IsRewardedVideoAvailable` and Gley's `IsRewardedVideoAvailable` are left
 * untouched: forcing them to `true` would make the in-level path call `ShowRewardedVideo` and
 * then wait for a callback that never arrives, stalling the revive. The gates are dropped at
 * the caller instead. `UnlockAll.Start`, `InappCanvas.BuyGolds` and `InappCanvas.BuyDiamonds`
 * are dead `ret` stubs in 1.9.6 and are not touched anywhere in this patch set.
 *
 * ============================================================================
 * CAVEAT
 * ============================================================================
 * The gates make the gift button available offline and the grant is instant, so the 100-diamond
 * claim is **repeatable without limit**. That is the intended "free ad reward" behaviour, but
 * it is unbounded.
 *
 * ============================================================================
 * OFFSETS
 * ============================================================================
 * `arm64-v8a`: `fileOffset = rva - 0x4000`. `armeabi-v7a`: `fileOffset = rva`; the lib is
 * **A32, not Thumb**. All ten verify windows occur exactly once in their library; the helper
 * re-counts at patch time and writes nothing unless every anchor of an ABI validates.
 */
private const val PATCH_NAME = "Ninja Arashi 2 free ad rewards"

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "AdCreator.ShowFullAD -> CompleteMethod(this, true) + epilogue",
        rva = 0x1523134,
        fileOffset = 0x151F134,
        verifyHex = "E0031FAADDBE039420020036E8D600B0",
        // mov x0, x19        ; this (AdCreator)
        // mov w1, #1         ; completed = true
        // bl 0x15231AC       ; AdCreator.CompleteMethod
        // b  0x1523180       ; this function's epilogue (pops the frame, then ret)
        replacementHex = "E00313AA210080521C00009410000014",
    ),
    NativeAnchor(
        label = "AdCreator.CheckAd -> GiftAdReady is always true",
        rva = 0x152319C,
        fileOffset = 0x151F19C,
        verifyHex = "0800001268820039FE4FC1A8C0035FD6",
        // mov w8, #1  (was: and w8, w0, #1 — the strb below still stores it)
        replacementHex = "28008052",
    ),
    NativeAnchor(
        label = "MainCanvas.Update -> drop the adhas >= 1 gate",
        rva = 0x15BD9B8,
        fileOffset = 0x15B99B8,
        verifyHex = "EB020054E0031FAA79BF5794617640BD",
        // nop  (was: b.lt <skip the gift button>)
        replacementHex = "1F2003D5",
    ),
    NativeAnchor(
        label = "MainCanvas.Update -> drop the IsRewardedVideoAvailable gate",
        rva = 0x15BD9E0,
        fileOffset = 0x15B99E0,
        verifyHex = "A0010036602240F92800805268820139",
        // nop  (was: tbz w0, #0, <skip the gift button>)
        replacementHex = "1F2003D5",
    ),
    NativeAnchor(
        label = "MainEventsLog.liveAds -> XemAdHoiSinh(true)",
        rva = 0x15B9C1C,
        fileOffset = 0x15B5C1C,
        verifyHex = "FE0F1EF8F44F01A9D4E10090F30300AA88626739",
        // mov w1, #1 ; b 0x15B9CD0 (MainEventsLog.XemAdHoiSinh) — tail call, frame never pushed
        replacementHex = "210080522C000014",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "AdCreator.ShowFullAD -> CompleteMethod(this, true) + epilogue",
        rva = 0xAF5D14,
        fileOffset = 0xAF5D14,
        verifyHex = "000050E33088BD0844009FE500009FE7",
        // mov r0, r4                 ; this (AdCreator)
        // mov r1, #1                 ; completed = true
        // bl 0xAF5D88                ; AdCreator.CompleteMethod
        // pop {r4, r5, r11, pc}      ; this function's own frame
        replacementHex = "0400A0E10110A0E3190000EB3088BDE8",
    ),
    NativeAnchor(
        label = "AdCreator.CheckAd -> GiftAdReady is always true",
        rva = 0xAF5D80,
        fileOffset = 0xAF5D80,
        verifyHex = "1000C4E51080BDE830482DE90040A0E1",
        // mov r0, #1 ; strb r0, [r4, #0x10]  (the original pop {r4, pc} follows untouched)
        replacementHex = "0100A0E31000C4E5",
    ),
    NativeAnchor(
        label = "MainCanvas.Update -> drop the adhas >= 1 gate",
        rva = 0xBCEE84,
        fileOffset = 0xBCEE84,
        verifyHex = "090000BA0000A0E324AB65EB120A94ED",
        // nop  (was: blt <skip the gift button>)
        replacementHex = "0000A0E1",
    ),
    NativeAnchor(
        label = "MainCanvas.Update -> drop the IsRewardedVideoAvailable gate",
        rva = 0xBCEEC0,
        fileOffset = 0xBCEEC0,
        verifyHex = "3088BD08245094E50100A0E33400C4E5",
        // nop  (was: popeq {r4, r5, r11, pc} — the early return when no ad is available)
        replacementHex = "0000A0E1",
    ),
    NativeAnchor(
        label = "MainEventsLog.liveAds -> XemAdHoiSinh(true)",
        rva = 0xBC9C50,
        fileOffset = 0xBC9C50,
        verifyHex = "30482DE9BC509FE50040A0E105508FE00000D5E5000050E30700001AA8009FE5" +
            "00009FE78070F6EB",
        // mov r1, #1 ; b 0xBC9D2C (MainEventsLog.XemAdHoiSinh) — tail call
        replacementHex = "0110A0E3340000EA",
    ),
)

@Suppress("unused")
val ninjaArashi2FreeAdRewardsPatch = rawResourcePatch(
    name = "Free Ad Rewards",
    description = "Every \"watch an ad\" button pays out immediately without playing an ad: " +
        "the main-menu gift grants 100 diamonds and the in-level revive button revives you. " +
        "Works offline. The gift can be claimed repeatedly — the cooldown and availability " +
        "checks are removed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NINJA_ARASHI_2)

    execute {
        applyNativeEdits(
            patchName = PATCH_NAME,
            targets = listOf(
                AbiEdits("arm64-v8a", ARM64_ANCHORS),
                AbiEdits("armeabi-v7a", ARM32_ANCHORS),
            ),
        )
        println(
            "$PATCH_NAME: AdCreator.ShowFullAD grants via CompleteMethod(true), both" +
                " MainCanvas.Update ad gates are removed, AdCreator.CheckAd always reports" +
                " ready and MainEventsLog.liveAds revives without an ad, on every shipped ABI" +
                " (static in-place edit, no launch code).",
        )
    }
}

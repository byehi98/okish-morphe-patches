package app.ninjaarashi.patches.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.ninjaarashi.patches.shared.AbiEdits
import app.ninjaarashi.patches.shared.Constants.COMPATIBILITY_NINJA_ARASHI
import app.ninjaarashi.patches.shared.NativeAnchor
import app.ninjaarashi.patches.shared.applyNativeEdits

/**
 * Ninja Arashi 1.9.3 · **Free Ad Rewards** — "watch ad" buttons pay out with no ad.
 *
 * Unity 6.2 / IL2CPP: the whole reward flow is C# compiled into `libil2cpp.so`, and
 * `AdCreator.ShowFullAD` / `AdCreator.CompleteMethod` have **zero direct `bl` callers**
 * (they are UnityEvent/delegate targets), so the only way to change the flow is to edit
 * their bodies. Two edits per ABI, both proven unique at 48 bytes:
 *
 * ============================================================================
 * WHY BOTH EDITS ARE NEEDED
 * ============================================================================
 * ```
 * mainlv.ShowRewardAd()                 arm64 0x13FE900 / armv7 0xA48540
 *   AdCreator.CheckAd()                 arm64 0x13D7114 / armv7 0xA12168
 *       GiftAdReady = MobileAdsManager.IsRewardedVideoAvailable()
 *   if (!GiftAdReady) return            <-- offline => the button does nothing
 *   AdCreator.ShowFullAD(0)             arm64 0x13D707C / armv7 0xA120C0
 *       this.lv = 0
 *       if (!IsRewardedVideoAvailable()) return
 *       API.ShowRewardedVideo(CompleteMethod)
 * ```
 * Editing only the show path is not enough: with no ad loaded, `CheckAd` leaves
 * `GiftAdReady = false` and the reward button is dead before `ShowFullAD` is ever
 * reached. Edit B1 forces the availability answer to `true`; edit B2 then jumps
 * straight to the game's own completion handler, which is what actually grants the
 * reward. Together the reward works **offline** and instantly.
 *
 * ============================================================================
 * EDIT B1 — `AdCreator.CheckAd`: always "rewarded ad available"
 * ============================================================================
 * ```
 * arm64 0x13D7148: mov w0, #1     (was: bl MobileAdsManager.IsRewardedVideoAvailable)
 * armv7 0xA12198:  mov r0, #1     (was: bl MobileAdsManager.IsRewardedVideoAvailable)
 * ```
 * The following `and w9,w0,#1` / `strb` stores the returned 1 into `GiftAdReady`, so
 * the flag ends up exactly as it would after a successful availability probe.
 *
 * ============================================================================
 * EDIT B2 — `AdCreator.ShowFullAD`: grant the reward, skip the ad
 * ============================================================================
 * Branch to `CompleteMethod(true)` after `this.lv` has already been stored:
 * ```
 * arm64 0x13D70C0: mov x0, x19 ; mov w1, #1 ; b 0x13D7168   (CompleteMethod)
 * armv7 0xA12108:  mov r0, r4  ; mov r1, #1 ; b 0xA121C0    (CompleteMethod)
 * ```
 * `x19`/`r4` hold `this` at that point (`mov x19,x0` at 0x13D708C / `mov r4,r0` at
 * 0xA120C8) and `this.lv` was stored at 0x13D70BC / 0xA12104, so `CompleteMethod` sees
 * the same state a real completed video would produce:
 *
 * ```
 * lv == 0  -> mainlv.ShowComplete()   Coin += 2000 ; DM += 50   (shop reward button)
 * lv != 0  -> MainEventsLog.XemXongVideo() -> hoisinh()          (in-level revive)
 * ```
 * The bytes overwritten are `bl IsRewardedVideoAvailable`, the `tbz`/`cmp` guard and the
 * first instruction of the delegate setup — all dead once we branch away.
 *
 * ============================================================================
 * COEXISTENCE WITH "Remove Ads"
 * ============================================================================
 * `RemoveAds` only gates `ShowAD` / `ShowAD30s` / `CanShowAds`; it does not gate this
 * reward flow. Both patches can be enabled together: no ads play anywhere, and the
 * reward buttons still grant instantly.
 *
 * ============================================================================
 * OFFSETS
 * ============================================================================
 * `arm64-v8a`: `fileOffset = rva - 0x4000` (exec `PT_LOAD` vaddr 0x1123fa0 / off
 * 0x111ffa0). `armeabi-v7a`: `fileOffset = rva`; the lib is **A32, not Thumb**.
 * Every 48-byte verify window below occurs exactly once in its library; the helper
 * re-counts at patch time and writes nothing unless the count is 1.
 */
private const val PATCH_NAME = "Ninja Arashi free ad rewards"

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "AdCreator.CheckAd — GiftAdReady is always true",
        rva = 0x13D7148,
        fileOffset = 0x13D3148,
        verifyHex = "53360194680240F9F44F41A909000012085D40F909010039FE0742F8C0035FD6" +
            "FE57BEA9F44F01A935D800F0F303012A",
        // mov w0, #1  (was `bl MobileAdsManager.IsRewardedVideoAvailable`)
        replacementHex = "20008052",
    ),
    NativeAnchor(
        label = "AdCreator.ShowFullAD — branch straight to CompleteMethod(true)",
        rva = 0x13D70C0,
        fileOffset = 0x13D30C0,
        verifyHex = "7536019420020036A8C90090B4C90090084543F9944243F9000140F93D5AFB97" +
            "820240F9E10313AAE3031FAAF40300AA",
        // mov x0, x19        ; this (AdCreator)
        // mov w1, #1         ; completed = true
        // ldp x20, x19, [sp, #0x10]   ┐ unwind THIS function's frame
        // ldp x30, x21, [sp], #0x20   ┘ before the tail branch
        // b 0x13D7168        ; CompleteMethod
        //
        // The epilogue is mandatory: `ShowFullAD` pushes 0x20 bytes
        // (`stp x30,x21,[sp,#-0x20]!` + `stp x20,x19,[sp,#0x10]`). A bare
        // `b CompleteMethod` would leave that frame allocated, so CompleteMethod's
        // `ret` would pop the wrong x30 and resume with a misaligned SP — the exact
        // crash seen on device before this fix.
        replacementHex = "E00313AA21008052F44F41A9FE57C2A826000014",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "AdCreator.CheckAd — GiftAdReady is always true",
        rva = 0xA12198,
        fileOffset = 0xA12198,
        verifyHex = "D9A001EB18109FE501109FE7001091E55C1091E50000C1E51080BDE833ACD401" +
            "D8F7C501BCF7C50170402DE934619FE5",
        // mov r0, #1  (was `bl MobileAdsManager.IsRewardedVideoAvailable`)
        replacementHex = "0100A0E3",
    ),
    NativeAnchor(
        label = "AdCreator.ShowFullAD — branch straight to CompleteMethod(true)",
        rva = 0xA12108,
        fileOffset = 0xA12108,
        verifyHex = "FDA001EB000050E37080BD0844009FE500009FE7000090E556B2FBEB0050A0E1" +
            "34009FE50410A0E10030A0E300009FE7",
        // mov r0, r4                 ; this (AdCreator)
        // mov r1, #1                 ; completed = true
        // pop {r4, r5, r6, lr}       ; unwind THIS function's frame (mandatory)
        // b 0xA121C0                 ; CompleteMethod
        replacementHex = "0400A0E10110A0E37040BDE8290000EA",
    ),
)

@Suppress("unused")
val ninjaArashiFreeAdRewardsPatch = rawResourcePatch(
    name = "Free Ad Rewards",
    description = "Every \"watch ad\" button pays out immediately without playing an ad: the " +
        "shop reward grants 2000 coins and 50 diamonds, and the in-level revive button " +
        "revives you. Works offline and can be tapped repeatedly.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NINJA_ARASHI)

    execute {
        applyNativeEdits(
            patchName = PATCH_NAME,
            targets = listOf(
                AbiEdits("arm64-v8a", ARM64_ANCHORS),
                AbiEdits("armeabi-v7a", ARM32_ANCHORS),
            ),
        )
        println(
            "$PATCH_NAME: AdCreator.CheckAd now always reports a rewarded ad as available and" +
                " AdCreator.ShowFullAD branches straight into CompleteMethod(true) on every" +
                " shipped ABI (static in-place edit, no launch code).",
        )
    }
}

package app.ninjaarashi2.patches.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.ninjaarashi2.patches.shared.AbiEdits
import app.ninjaarashi2.patches.shared.Constants.COMPATIBILITY_NINJA_ARASHI_2
import app.ninjaarashi2.patches.shared.NativeAnchor
import app.ninjaarashi2.patches.shared.applyNativeEdits

/**
 * Ninja Arashi 2 1.9.6 · **Remove Ads** — no banner, interstitial, app-open or rewarded
 * prompt ever plays, enforced in native code rather than in a preference.
 *
 * Unity 6000.2.6f2 / IL2CPP: the ad surface is entirely `Assembly-CSharp` compiled into
 * `libil2cpp.so`; DEX only carries the Unity player and the ad SDKs, so the flags are
 * patched where they are *read*.
 *
 * ============================================================================
 * EDIT 1 — `MobileAdsManager.CanShowAds -> false`
 * ============================================================================
 * ```
 * arm64 0x1618BE8  (file 0x1614BE8)   ->  mov w0, #0 ; ret
 * v7a   0xC4747C   (file 0xC4747C)    ->  mov r0, #0 ; bx lr
 * ```
 * This is the gate every banner / interstitial / app-open call site consults before it
 * talks to ironSource/LevelPlay, so returning `false` is a single choke point for the
 * whole "should an ad be shown" decision.
 *
 * ============================================================================
 * EDIT 2 — `AdCreator.ShowAD` forced onto its own ad-free branch
 * ============================================================================
 * `ShowAD` already contains the game's no-ads test and an early exit:
 * ```
 * arm64 0x152309C   cmp w0, #1
 *       0x15230A0   b.eq <epilogue>     ; "no ads" path: return before the SDK call
 *       0x15230A4   mov x0, xzr
 *       0x15230A8   bl  <SDK ShowAd>
 * v7a   0xAF5C58    cmp r0, #1
 *       0xAF5C5C    beq <epilogue>
 *       0xAF5C60    mov r0, #0
 *       0xAF5C64    bl  <SDK ShowAd>
 * ```
 * The `cmp` is `nop`ed and the conditional branch is replaced with an **unconditional**
 * branch to that very same epilogue target — the `b.eq`/`beq` displacement is reused, so
 * the destination is bit-for-bit the path the game takes when its no-ads preference is
 * already set:
 * ```
 * arm64 0x152309C -> nop ; b 0x15230EC
 * v7a   0xAF5C58  -> nop ; b 0xAF5C78
 * ```
 * This covers the rewarded/rewarded-30s entry points too, so an ad can never slip past
 * edit 1 through a second gate.
 *
 * ============================================================================
 * NO PREFERENCE SEEDING NEEDED
 * ============================================================================
 * The "Free In-App Purchases" patch's `BuyRemoveAd` button runs the game's real grant path
 * (`BuyRemoveAdsSuccessed` -> `PlayerPrefs.SetInt` + `Save` + `UpdateRemoveAds`), so the shop
 * flips to "owned" on its own. This patch is therefore purely native and ABI-symmetric.
 *
 * ============================================================================
 * OFFSETS
 * ============================================================================
 * `arm64-v8a`: `fileOffset = rva - 0x4000`. `armeabi-v7a`: `fileOffset = rva`; the lib is
 * **A32, not Thumb**. Both verify windows occur exactly once per library and the helper
 * re-validates before writing.
 */
private const val PATCH_NAME = "Ninja Arashi 2 remove ads"

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "MobileAdsManager.CanShowAds -> false",
        rva = 0x1618BE8,
        fileOffset = 0x1614BE8,
        verifyHex = "FE0F1EF8F44F01A9D4DE00B053CF00B0",
        // mov w0, #0 ; ret
        replacementHex = "00008052C0035FD6",
    ),
    NativeAnchor(
        label = "AdCreator.ShowAD -> always take the ad-free branch",
        rva = 0x152309C,
        fileOffset = 0x151F09C,
        verifyHex = "1F04007160020054E0031FAADDBE0394",
        // nop (was: cmp w0, #1) ; b 0x15230EC (same target as the original b.eq)
        replacementHex = "1F2003D513000014",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "MobileAdsManager.CanShowAds -> false",
        rva = 0xC4747C,
        fileOffset = 0xC4747C,
        verifyHex = "10402DE95C409FE504408FE00000D4E5000050E30400001A4C009FE500009FE7" +
            "767AF4EB",
        // mov r0, #0 ; bx lr
        replacementHex = "0000A0E31EFF2FE1",
    ),
    NativeAnchor(
        label = "AdCreator.ShowAD -> always take the ad-free branch",
        rva = 0xAF5C58,
        fileOffset = 0xAF5C58,
        verifyHex = "010050E30500000A0000A0E3D82A05EB",
        // nop (was: cmp r0, #1) ; b 0xAF5C78 (same target as the original beq)
        replacementHex = "0000A0E1050000EA",
    ),
)

@Suppress("unused")
val ninjaArashi2RemoveAdsPatch = rawResourcePatch(
    name = "Remove Ads",
    description = "Removes every ad from the game: banners, interstitials, the app-open ad " +
        "and the rewarded-video prompts are all suppressed in native code, so the ad SDK is " +
        "never asked to display anything. Works offline and needs no purchase.",
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
            "$PATCH_NAME: MobileAdsManager.CanShowAds now returns false and AdCreator.ShowAD" +
                " always takes its ad-free branch on every shipped ABI (static in-place edit," +
                " no launch code).",
        )
    }
}

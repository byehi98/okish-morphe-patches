package app.sonicdash.patches.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch
import app.sonicdash.patches.shared.Constants.COMPATIBILITY_SONIC_DASH

/**
 * Sonic Dash Run 10.7.0 · **Remove Ads** — banners, interstitials and ad pop-ups never
 * appear because the game's own "paid user / ads hidden" predicates are forced true.
 *
 * Unity IL2CPP title (`com.sega.sonicdash`). 10.7.0 is a split `.apks` bundle
 * (`base.apk` + `split_config.arm64_v8a` + `split_config.armeabi_v7a` +
 * `split_UnityDataAssetPack`), so every predicate exists twice on disk — once per ABI —
 * and both copies are patched:
 *
 * | ABI | library | RVA → file offset |
 * |---|---|---|
 * | arm64-v8a | `lib/arm64-v8a/libil2cpp.so` (42,536,864 B) | `file = RVA − 0x4000` |
 * | armeabi-v7a | `lib/armeabi-v7a/libil2cpp.so` (35,163,652 B) | `file = RVA` (single RX LOAD at vaddr 0) |
 *
 * The Java/DEX layer is only SDK glue (AdMob bridge, Firebase, AppsFlyer, Play Billing
 * client) and contains no premium or ad gate, so the patch edits the native libraries
 * while the APK is built — DEX untouched, nothing loaded at runtime, edits survive every
 * launch.
 *
 * ### Why five sites
 *
 * The game models "no ads" as `IsPaidUser`. There is no single flag to flip: several
 * consumers read `Purchases.<IsPaidUser>k__BackingField` directly and never call the
 * property getter, so every reader has to be forced. All five are plain boolean returns,
 * replaced at their first instruction with `mov w0, #1 ; ret`
 * (arm64 `20 00 80 52 C0 03 5F D6`, arm32 `01 00 A0 E3 1E FF 2F E1`):
 *
 * | # | Symbol | arm64 file | armv7 file | Shipped body |
 * |---|---|---|---|---|
 * | 1 | `SonicDash.Server.Purchases.get_IsPaidUser` | `0x1323CF4` | `0xA66AA8` | `ldrb w0, [x0, #0x10] ; ret` / `ldr r0, [r0, #8] ; bx lr` |
 * | 2 | `RemoveAdsButton.IsPaidUser` | `0x11FB6C4` | `0x8FB40C` | getter call + `cset ne` |
 * | 3 | `HardlightProject.AdVisibilityManager.AreAdsHidden` | `0x1334260` | `0xA7A2A8` | `ldrb w0, [x0, #0x20] ; ret` / `ldr r0, [r0, #0x10] ; bx lr` |
 * | 4 | `Hardlight.Advertisement.DashAdSettings.IsPaidUser` | `0x14182B4` | `0xB9366C` | getter call + `cset ne` |
 * | 5 | `SonicDash.Ads.BannerVisibilityManager.IsPaidUser` | `0x1332C1C` | `0xA78858` | `ldrb w0, [[x0+0x48]+0x20]+0x10 ; ret` / `ldr r4, [r0, #0x24]` + getter call |
 *
 * `DashAdSettings.InternalIsAdFormatEnabled` needs no separate patch: for the banner and
 * interstitial formats (0 and 1) it `bl`s `DashAdSettings.IsPaidUser` and returns
 * `!IsPaidUser`, so site 4 turns both formats off. Format 2 (rewarded video) is gated by
 * `FeatureSupport.IsLowEndDevice` instead, which is why ad-reward buttons keep working
 * (see the separate "Free Ad Rewards" patch).
 *
 * ### Verification
 *
 * Every anchor below occurs exactly once in each 10.7.0 library at the stated file offset.
 * The rewrite only touches the leading bytes of each method (the replacement is the same
 * length as the anchor, and keeps the untouched tail of the anchor in place), so the rest
 * of the original prologue becomes dead code after the injected `ret`. On armv7 the
 * `RemoveAdsButton` and `DashAdSettings` anchors share the same 32-byte compiler prologue,
 * so 40-byte anchors are used to keep each unique. `hexPatch` throws when an anchor stops
 * matching, so a future game version fails the build instead of silently shipping an
 * unpatched APK.
 *
 * `ignoreMissingTargetFiles = true` keeps single-ABI bundles working: a build that ships
 * only the arm64 (or only the armv7) split simply does not have the other library path.
 *
 * ### Version support
 *
 * 10.7.0 only. 10.6.0 is no longer supported — the methods moved and the anchor bytes
 * changed between the two versions, so the 10.6.0 patterns do not match this build.
 */
private const val LIB_ARM64 = "lib/arm64-v8a/libil2cpp.so"
private const val LIB_ARM32 = "lib/armeabi-v7a/libil2cpp.so"

private val adRemovalSites: HexPatchBuilder.() -> Unit = {
    // ── arm64-v8a ─────────────────────────────────────────────────────────────
    // 1. SonicDash.Server.Purchases.get_IsPaidUser @ file 0x1323CF4: always a paid user.
    "00 40 40 39 C0 03 5F D6 01 40 00 39 C0 03 5F D6 40 00 80 52 C0 03 5F D6" asPatternTo
    "20 00 80 52 C0 03 5F D6 01 40 00 39 C0 03 5F D6 40 00 80 52 C0 03 5F D6" inFile LIB_ARM64

    // 2. RemoveAdsButton.IsPaidUser @ file 0x11FB6C4: always a paid user.
    "FE 0F 1E F8 F4 4F 01 A9 F4 B4 00 B0 F3 03 00 AA 88 AA 70 39 C8 00 00 37" asPatternTo
    "20 00 80 52 C0 03 5F D6 F4 B4 00 B0 F3 03 00 AA 88 AA 70 39 C8 00 00 37" inFile LIB_ARM64

    // 3. HardlightProject.AdVisibilityManager.AreAdsHidden @ file 0x1334260: ads hidden.
    "00 80 40 39 C0 03 5F D6 FE 0F 1D F8 F6 57 01 A9 F4 4F 02 A9 35 AB 00 B0" asPatternTo
    "20 00 80 52 C0 03 5F D6 FE 0F 1D F8 F6 57 01 A9 F4 4F 02 A9 35 AB 00 B0" inFile LIB_ARM64

    // 4. Hardlight.Advertisement.DashAdSettings.IsPaidUser @ file 0x14182B4: always a paid
    //    user (this also forces InternalIsAdFormatEnabled false for banner + interstitial).
    "FE 0F 1E F8 F4 4F 01 A9 14 A4 00 D0 F3 03 00 AA 88 A6 4B 39 C8 00 00 37" asPatternTo
    "20 00 80 52 C0 03 5F D6 14 A4 00 D0 F3 03 00 AA 88 A6 4B 39 C8 00 00 37" inFile LIB_ARM64

    // 5. SonicDash.Ads.BannerVisibilityManager.IsPaidUser @ file 0x1332C1C: always a paid user.
    "FE 0F 1F F8 08 24 40 F9 C8 00 00 B4 08 11 40 F9 88 00 00 B4 00 41 40 39" asPatternTo
    "20 00 80 52 C0 03 5F D6 C8 00 00 B4 08 11 40 F9 88 00 00 B4 00 41 40 39" inFile LIB_ARM64

    // ── armeabi-v7a (A32 / ARM, not Thumb; file offset == RVA) ─────────────────
    // 1. Purchases.get_IsPaidUser @ 0xA66AA8.
    "08 00 D0 E5 1E FF 2F E1 08 10 C0 E5 1E FF 2F E1 02 00 A0 E3 1E FF 2F E1" asPatternTo
    "01 00 A0 E3 1E FF 2F E1 08 10 C0 E5 1E FF 2F E1 02 00 A0 E3 1E FF 2F E1" inFile LIB_ARM32

    // 2. RemoveAdsButton.IsPaidUser @ 0x8FB40C. Shares its 32-byte prologue with site 4,
    //    so a 40-byte window is used to keep the match unique.
    "30 48 2D E9 78 50 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 04 00 00 1A 64 00 9F E5 00 00 9F E7 8D 08 F9 EB" asPatternTo
    "01 00 A0 E3 1E FF 2F E1 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 04 00 00 1A 64 00 9F E5 00 00 9F E7 8D 08 F9 EB" inFile LIB_ARM32

    // 3. AdVisibilityManager.AreAdsHidden @ 0xA7A2A8.
    "10 00 D0 E5 1E FF 2F E1 F0 48 2D E9 54 51 9F E5 00 40 A0 E1" asPatternTo
    "01 00 A0 E3 1E FF 2F E1 F0 48 2D E9 54 51 9F E5 00 40 A0 E1" inFile LIB_ARM32

    // 4. DashAdSettings.IsPaidUser @ 0xB9366C (40-byte window, see site 2).
    "30 48 2D E9 78 50 9F E5 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 04 00 00 1A 64 00 9F E5 00 00 9F E7 F5 A7 EE EB" asPatternTo
    "01 00 A0 E3 1E FF 2F E1 00 40 A0 E1 05 50 8F E0 00 00 D5 E5 00 00 50 E3 04 00 00 1A 64 00 9F E5 00 00 9F E7 F5 A7 EE EB" inFile LIB_ARM32

    // 5. BannerVisibilityManager.IsPaidUser @ 0xA78858.
    "10 40 2D E9 24 40 90 E5 00 00 54 E3 00 00 00 1A 27 14 F3 EB" asPatternTo
    "01 00 A0 E3 1E FF 2F E1 00 00 54 E3 00 00 00 1A 27 14 F3 EB" inFile LIB_ARM32
}

@Suppress("unused")
val removeAdsPatch = rawResourcePatch(
    name = "Remove Ads",
    description = "Stops banners, full-screen interstitials and ad pop-ups from appearing. " +
        "Runs and menus carry on as normal — the ads just never show up.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SONIC_DASH)

    dependsOn(hexPatch(ignoreMissingTargetFiles = true, block = adRemovalSites))
}

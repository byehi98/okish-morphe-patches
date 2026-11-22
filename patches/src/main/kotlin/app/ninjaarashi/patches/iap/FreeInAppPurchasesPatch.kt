package app.ninjaarashi.patches.iap

import app.morphe.patcher.patch.rawResourcePatch
import app.ninjaarashi.patches.shared.AbiEdits
import app.ninjaarashi.patches.shared.Constants.COMPATIBILITY_NINJA_ARASHI
import app.ninjaarashi.patches.shared.NativeAnchor
import app.ninjaarashi.patches.shared.applyNativeEdits

/**
 * Ninja Arashi 1.9.3 · **Free In-App Purchases** — the four shop buttons grant their
 * item directly, with no Play Billing sheet.
 *
 * Unity 6.2 / IL2CPP: `InappCanvas.BuyGolds/BuyDiamonds/BuyRemoveAds/BuyHalo` are
 * UnityEvent button targets (**0 direct `bl` callers**) and each one simply tail-calls
 * `IAPManager.BuyProductID(...)`. Each entry is redirected to the game's own local grant
 * method with a 4–8 byte branch placed over the method prologue:
 *
 * | Button | ABI | file offset | replacement | meaning |
 * |---|---|---|---|---|
 * | `BuyGolds` | arm64 | `0x13F1EB8` | `movz w1,#0x4E20 ; b AddGolds` | grant 20,000 coins |
 * | `BuyGolds` | armv7 | `0xA3D00C` | `movw r1,#0x4E20 ; b AddGolds` | grant 20,000 coins |
 * | `BuyDiamonds` | arm64 | `0x13F1F08` | `movz w1,#0x7D0 ; b AddDM` | grant 2,000 diamonds |
 * | `BuyDiamonds` | armv7 | `0xA3D070` | `movw r1,#0x7D0 ; b AddDM` | grant 2,000 diamonds |
 * | `BuyRemoveAds` | arm64 | `0x13F1F58` | `b AddRemoveAds` | unlock ad-free |
 * | `BuyRemoveAds` | armv7 | `0xA3D0D0` | `b AddRemoveAds` | unlock ad-free |
 * | `BuyHalo` | arm64 | `0x13F1FDC` | `b AddHalo` | unlock the Halo pack |
 * | `BuyHalo` | armv7 | `0xA3D160` | `b AddHalo` | unlock the Halo pack |
 *
 * Branch targets (verified by decoding each encoded `b`, and by patching a copy and
 * reading the result back):
 *
 * ```
 *             arm64                  armv7
 * AddGolds    0x13F5324              0xA3C228
 * AddDM       0x13F5370              0xA3C27C
 * AddRemoveAds 0x13F5410             0xA3C330
 * AddHalo     0x13F54DC              0xA3C418
 * ```
 *
 * `AddGolds`/`AddDM` set the static pending-grant fields `InappCanvas.c` / `.d`, which
 * `InappCanvas.Update` applies on the next frame (`Coin += c`, `DM += d`, `Save()`), so
 * the balances are persisted normally and no receipt is ever checked. `AddRemoveAds` /
 * `AddHalo` write the prefs (`RemoveAds`, `Halo`, `iap_noads`, `iap_halo`) and set the
 * static `a` flag that forces `InappCanvas.Refresh()`, so the shop redraws as "owned".
 *
 * ============================================================================
 * INTERACTION WITH THE OTHER PATCHES
 * ============================================================================
 * `BuyRemoveAds` / `BuyHalo` duplicate what "Remove Ads" (pref pre-seed) already
 * achieves, but keep the shop UI consistent — tapping "purchase" succeeds and the tile
 * flips to owned. All three patches are independently selectable and order-independent.
 *
 * ============================================================================
 * OFFSETS
 * ============================================================================
 * `arm64-v8a`: `fileOffset = rva - 0x4000`. `armeabi-v7a`: `fileOffset = rva`; the lib
 * is **A32, not Thumb**. Every 48-byte verify window occurs exactly once in its library;
 * the helper re-counts at patch time and refuses to write anything unless all four
 * anchors of an ABI validate.
 */
private const val PATCH_NAME = "Ninja Arashi free in-app purchases"

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "InappCanvas.BuyGolds -> AddGolds(20000)",
        rva = 0x13F5EB8,
        fileOffset = 0x13F1EB8,
        verifyHex = "FE0F1EF8F44F01A953D700B0B4C800D0683A5139947E45F9C8000037A0C800D0" +
            "007C45F92FDEFA9728008052683A1139",
        // movz w1, #0x4E20 (20000) ; b 0x13F5324 (AddGolds)
        replacementHex = "01C489521AFDFF17",
    ),
    NativeAnchor(
        label = "InappCanvas.BuyDiamonds -> AddDM(2000)",
        rva = 0x13F5F08,
        fileOffset = 0x13F1F08,
        verifyHex = "FE0F1EF8F44F01A953D700B0B4C800D0683E5139947E45F9C8000037A0C800D0" +
            "007C45F91BDEFA9728008052683E1139",
        // movz w1, #0x7D0 (2000) ; b 0x13F5370 (AddDM)
        replacementHex = "01FA805219FDFF17",
    ),
    NativeAnchor(
        label = "InappCanvas.BuyRemoveAds -> AddRemoveAds()",
        rva = 0x13F5F58,
        fileOffset = 0x13F1F58,
        verifyHex = "FE0F1EF8F44F01A953D700B0B4C800D068425139943643F928010037A0C800D0" +
            "007C45F907DEFA97A0C800D0003443F9",
        // b 0x13F5410 (AddRemoveAds)
        replacementHex = "2EFDFF17",
    ),
    NativeAnchor(
        label = "InappCanvas.BuyHalo -> AddHalo()",
        rva = 0x13F5FDC,
        fileOffset = 0x13F1FDC,
        verifyHex = "FE0F1EF8F44F01A953D700B0B4C800D06846513994CE45F928010037A0C800D0" +
            "007C45F9E6DDFA97A0C800B000CC45F9",
        // b 0x13F54DC (AddHalo)
        replacementHex = "40FDFF17",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "InappCanvas.BuyGolds -> AddGolds(20000)",
        rva = 0xA3D00C,
        fileOffset = 0xA3D00C,
        verifyHex = "10402DE94C409FE504408FE00000D4E5000050E30400001A3C009FE500009FE7" +
            "0006FBEB0100A0E30000C4E52C009FE5",
        // movw r1, #0x4E20 (20000) ; b 0xA3C228 (AddGolds)
        replacementHex = "201E04E384FCFFEA",
    ),
    NativeAnchor(
        label = "InappCanvas.BuyDiamonds -> AddDM(2000)",
        rva = 0xA3D070,
        fileOffset = 0xA3D070,
        // The `bl` displacement here is unique to BuyDiamonds (`E705FBEB`); a copy of the
        // BuyGolds window would not match. 48 B is exactly one occurrence in the lib.
        verifyHex = "10402DE948409FE504408FE00000D4E5000050E30400001A38009FE500009FE7" +
            "E705FBEB0100A0E30000C4E528009FE5",
        // movw r1, #0x7D0 (2000) ; b 0xA3C27C (AddDM)
        replacementHex = "D01700E380FCFFEA",
    ),
    NativeAnchor(
        label = "InappCanvas.BuyRemoveAds -> AddRemoveAds()",
        rva = 0xA3D0D0,
        fileOffset = 0xA3D0D0,
        // Differs from BuyHalo only in the two `bl` displacements, which this 48 B window
        // covers — hence the window is unique even though the prologues are identical.
        verifyHex = "10402DE970409FE504408FE00000D4E5000050E30700001A60009FE500009FE7" +
            "CF05FBEB58009FE500009FE7CC05FBEB",
        // b 0xA3C330 (AddRemoveAds)
        replacementHex = "96FCFFEA",
    ),
    NativeAnchor(
        label = "InappCanvas.BuyHalo -> AddHalo()",
        rva = 0xA3D160,
        fileOffset = 0xA3D160,
        verifyHex = "10402DE970409FE504408FE00000D4E5000050E30700001A60009FE500009FE7" +
            "AB05FBEB58009FE500009FE7A805FBEB",
        // b 0xA3C418 (AddHalo)
        replacementHex = "ACFCFFEA",
    ),
)

@Suppress("unused")
val ninjaArashiFreeInAppPurchasesPatch = rawResourcePatch(
    name = "Free In-App Purchases",
    description = "The four shop buttons grant their item directly instead of opening Google " +
        "Play billing: 20,000 coins, 2,000 diamonds, Remove Ads and the Halo pack. No payment " +
        "sheet appears and the purchases are saved normally.",
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
            "$PATCH_NAME: InappCanvas.BuyGolds/BuyDiamonds/BuyRemoveAds/BuyHalo now branch" +
                " straight to the local grant methods on every shipped ABI (static in-place" +
                " edit, no launch code).",
        )
    }
}

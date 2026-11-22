package app.ninjaarashi2.patches.iap

import app.morphe.patcher.patch.rawResourcePatch
import app.ninjaarashi2.patches.shared.AbiEdits
import app.ninjaarashi2.patches.shared.Constants.COMPATIBILITY_NINJA_ARASHI_2
import app.ninjaarashi2.patches.shared.NativeAnchor
import app.ninjaarashi2.patches.shared.applyNativeEdits

/**
 * Ninja Arashi 2 1.9.6 · **Free In-App Purchases** — the five shop buttons grant their
 * item directly, with no Play Billing sheet and no payment.
 *
 * Unity 6000.2.6f2 / IL2CPP: the entire shop is C# compiled into `libil2cpp.so`. Every
 * `ShopPanel.Buy*` is a **singleton thunk** (a ~0x54-byte UnityEvent target with zero
 * direct `bl` callers) whose tail is `b IAPManager.Buy<Product>`. Each thunk's *entry* is
 * rewritten in place with the game's own local grant path:
 *
 * ```
 * str x30, [sp, #-0x10]!         save the UnityEvent return address
 * bl  MainCanvas.get_Instance    -> x0 = MainCanvas.Instance
 * mov w1, #<index>               -> only for the diamond buttons (1 / 2 / 5 / 10)
 * bl  MainCanvas.Buy<X>Successed -> the exact method a real receipt runs
 * ldr x30, [sp], #0x10           restore the return address
 * ret
 * ```
 *
 * | Button | arm64 RVA / file | armeabi-v7a RVA == file |
 * |---|---|---|
 * | `ShopPanel.BuyRemoveAd`    | `0x15BE638` / `0x15BA638` | `0xBCFE8C` |
 * | `ShopPanel.BuyDiamond_1`   | `0x15BE68C` / `0x15BA68C` | `0xBCFEF4` |
 * | `ShopPanel.BuyDiamond_2`   | `0x15BE6E0` / `0x15BA6E0` | `0xBCFF5C` |
 * | `ShopPanel.BuyDiamond_5`   | `0x15BE734` / `0x15BA734` | `0xBCFFC4` |
 * | `ShopPanel.BuyDiamond_10`  | `0x15BE788` / `0x15BA788` | `0xBD002C` |
 *
 * Branch targets (decoded from the encoded `bl`/`b`, not guessed):
 *
 * ```
 *                        arm64        armeabi-v7a
 * MainCanvas.get_Instance  0x15BBE74    0xBCC890
 * BuyRemoveAdsSuccessed    0x15BDA24    0xBCEF20
 * BuyDiamondsSuccessed     0x15BDA80    0xBCEF88
 * ```
 *
 * ============================================================================
 * WHY THE ENTRY, AND WHY THE RETURN ADDRESS IS SAVED
 * ============================================================================
 * Both thunks open with `str x30,[sp,#-0x20]!` / `push {r4,lr}` and end with
 * `ldr x30,[sp],#0x20; b IAPManager.Buy<Product>` — i.e. the **tail branch depends on x30/lr
 * already holding the UnityEvent return address**. Replacing the entry with
 * `bl get_Instance; b BuyXSuccessed` would clobber x30, so the grant method would `ret`
 * straight back into the branch and loop forever. The rewrite therefore pushes its own
 * 16-byte (arm64) / 8-byte (v7a) frame, *calls* both helpers, restores the return address and
 * returns normally. Stack alignment is preserved (16 on arm64, 8 on v7a) and no callee-saved
 * register is touched.
 *
 * The grant methods themselves tail-branch (`BuyRemoveAdsSuccessed` ends with
 * `b MainCanvas.UpdateRemoveAds` after restoring its own frame), so a plain call is safe.
 * No code cave and no extra executable memory are needed.
 *
 * `BuyDiamondsSuccessed(int)` accepts only `1, 2, 5, 10` (bitmask `0x213`), which is
 * exactly the four diamond buttons shipped, so the index we pass is always a valid one.
 * The grant path also persists the result (`PlayerPrefs.SetInt` + `Save`) and refreshes the
 * shop UI, so purchases survive a restart exactly like a real one.
 *
 * `InappCanvas.BuyGolds` / `InappCanvas.BuyDiamonds` are bare `ret` stubs in 1.9.6 and are
 * deliberately **not** patched (dead code); `IAPManager.BuyArtifact_1..8` thunks are left
 * alone because artifacts are already covered by the receipt regrant path.
 *
 * ============================================================================
 * CAVEAT
 * ============================================================================
 * The rewritten thunks call `MainCanvas.get_Instance` without the null-check the original
 * had on `IAPManager.Instance`. `ShopPanel` only exists in the main-menu scene, where
 * `MainCanvas` is already alive, so this is safe in practice — but do not reuse this
 * rewrite in a scene that has no `MainCanvas`.
 *
 * ============================================================================
 * OFFSETS
 * ============================================================================
 * `arm64-v8a`: `fileOffset = rva - 0x4000` (`.text` vaddr `0x12057E0` / off `0x12017E0`).
 * `armeabi-v7a`: `fileOffset = rva`; the lib is **A32, not Thumb**. Every verify window
 * below was checked to occur exactly once in its library, and the helper re-counts at
 * patch time — it writes nothing unless all five anchors of an ABI validate.
 */
private const val PATCH_NAME = "Ninja Arashi 2 free in-app purchases"

// ── arm64-v8a ───────────────────────────────────────────────────────────────
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "ShopPanel.BuyRemoveAd -> MainCanvas.BuyRemoveAdsSuccessed()",
        rva = 0x15BE638,
        fileOffset = 0x15BA638,
        verifyHex = "FE0F1EF8F44F01A993E100F014D200F068426839",
        // str x30,[sp,#-0x10]! ; bl 0x15BBE74 (get_Instance) ;
        // bl 0x15BDA24 (BuyRemoveAdsSuccessed) ; ldr x30,[sp],#0x10 ; ret
        replacementHex = "FE0F1FF80EF6FF97F9FCFF97FE0741F8C0035FD6",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_1 -> MainCanvas.BuyDiamondsSuccessed(1)",
        rva = 0x15BE68C,
        fileOffset = 0x15BA68C,
        verifyHex = "FE0F1EF8F44F01A993E100F014D200F068466839940644F9",
        // str x30,[sp,#-0x10]! ; bl 0x15BBE74 ; mov w1,#1 ;
        // bl 0x15BDA80 (BuyDiamondsSuccessed) ; ldr x30,[sp],#0x10 ; ret
        replacementHex = "FE0F1FF8F9F5FF9721008052FAFCFF97FE0741F8C0035FD6",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_2 -> MainCanvas.BuyDiamondsSuccessed(2)",
        rva = 0x15BE6E0,
        fileOffset = 0x15BA6E0,
        verifyHex = "FE0F1EF8F44F01A993E100F014D200F0684A6839940644F9",
        // str x30,[sp,#-0x10]! ; bl 0x15BBE74 ; mov w1,#2 ;
        // bl 0x15BDA80 ; ldr x30,[sp],#0x10 ; ret
        replacementHex = "FE0F1FF8E4F5FF9741008052E5FCFF97FE0741F8C0035FD6",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_5 -> MainCanvas.BuyDiamondsSuccessed(5)",
        rva = 0x15BE734,
        fileOffset = 0x15BA734,
        verifyHex = "FE0F1EF8F44F01A993E100F014D200F0684E6839940644F9",
        // str x30,[sp,#-0x10]! ; bl 0x15BBE74 ; mov w1,#5 ;
        // bl 0x15BDA80 ; ldr x30,[sp],#0x10 ; ret
        replacementHex = "FE0F1FF8CFF5FF97A1008052D0FCFF97FE0741F8C0035FD6",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_10 -> MainCanvas.BuyDiamondsSuccessed(10)",
        rva = 0x15BE788,
        fileOffset = 0x15BA788,
        verifyHex = "FE0F1EF8F44F01A993E100F014D200F068526839940644F9",
        // str x30,[sp,#-0x10]! ; bl 0x15BBE74 ; mov w1,#10 ;
        // bl 0x15BDA80 ; ldr x30,[sp],#0x10 ; ret
        replacementHex = "FE0F1FF8BAF5FF9741018052BBFCFF97FE0741F8C0035FD6",
    ),
)

// ── armeabi-v7a ─────────────────────────────────────────────────────────────
private val ARM32_ANCHORS = listOf(
    NativeAnchor(
        label = "ShopPanel.BuyRemoveAd -> MainCanvas.BuyRemoveAdsSuccessed()",
        rva = 0xBCFE8C,
        fileOffset = 0xBCFE8C,
        verifyHex = "10402DE950409FE504408FE00000D4E5000050E30400001A40009FE500009FE7" +
            "F257F6EB",
        // push {r4,lr} ; bl 0xBCC890 (get_Instance) ;
        // bl 0xBCEF20 (BuyRemoveAdsSuccessed) ; pop {r4,pc}
        replacementHex = "10402DE97EF2FFEB21FCFFEB1080BDE8",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_1 -> MainCanvas.BuyDiamondsSuccessed(1)",
        rva = 0xBCFEF4,
        fileOffset = 0xBCFEF4,
        verifyHex = "10402DE950409FE504408FE00000D4E5000050E30400001A40009FE500009FE7" +
            "D857F6EB",
        // push {r4,lr} ; bl 0xBCC890 ; mov r1,#1 ;
        // bl 0xBCEF88 (BuyDiamondsSuccessed) ; pop {r4,pc}
        replacementHex = "10402DE964F2FFEB0110A0E320FCFFEB1080BDE8",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_2 -> MainCanvas.BuyDiamondsSuccessed(2)",
        rva = 0xBCFF5C,
        fileOffset = 0xBCFF5C,
        verifyHex = "10402DE950409FE504408FE00000D4E5000050E30400001A40009FE500009FE7" +
            "BE57F6EB",
        // push {r4,lr} ; bl 0xBCC890 ; mov r1,#2 ;
        // bl 0xBCEF88 ; pop {r4,pc}
        replacementHex = "10402DE94AF2FFEB0210A0E306FCFFEB1080BDE8",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_5 -> MainCanvas.BuyDiamondsSuccessed(5)",
        rva = 0xBCFFC4,
        fileOffset = 0xBCFFC4,
        verifyHex = "10402DE950409FE504408FE00000D4E5000050E30400001A40009FE500009FE7" +
            "A457F6EB",
        // push {r4,lr} ; bl 0xBCC890 ; mov r1,#5 ;
        // bl 0xBCEF88 ; pop {r4,pc}
        replacementHex = "10402DE930F2FFEB0510A0E3ECFBFFEB1080BDE8",
    ),
    NativeAnchor(
        label = "ShopPanel.BuyDiamond_10 -> MainCanvas.BuyDiamondsSuccessed(10)",
        rva = 0xBD002C,
        fileOffset = 0xBD002C,
        verifyHex = "10402DE950409FE504408FE00000D4E5000050E30400001A40009FE500009FE7" +
            "8A57F6EB",
        // push {r4,lr} ; bl 0xBCC890 ; mov r1,#10 ;
        // bl 0xBCEF88 ; pop {r4,pc}
        replacementHex = "10402DE916F2FFEB0A10A0E3D2FBFFEB1080BDE8",
    ),
)

@Suppress("unused")
val ninjaArashi2FreeInAppPurchasesPatch = rawResourcePatch(
    name = "Free In-App Purchases",
    description = "Every shop button grants its item directly instead of opening Google Play " +
        "billing: Remove Ads and the four diamond packs (1 / 2 / 5 / 10). No payment sheet " +
        "appears, the grant runs the game's own success handler, and the purchase is saved " +
        "normally.",
    default = false,
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
            "$PATCH_NAME: ShopPanel.BuyRemoveAd/BuyDiamond_1/2/5/10 now call" +
                " MainCanvas.BuyRemoveAdsSuccessed / BuyDiamondsSuccessed on every shipped" +
                " ABI (static in-place edit, no launch code).",
        )
    }
}

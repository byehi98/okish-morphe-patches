package app.headbasketball.patches.billing

import app.headbasketball.patches.shared.AbiEdits
import app.headbasketball.patches.shared.Constants.COMPATIBILITY_HEAD_BASKETBALL
import app.headbasketball.patches.shared.NativeAnchor
import app.headbasketball.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Head Basketball 4.6.4 · **N2 — Free store** (every shop purchase grants instantly).
 * Unity **2022.3.62f3 / IL2CPP** (`com.dnddream.HeadBasketball`, versionCode 471).
 * Static in-place byte edit of the shipped `lib/arm64-v8a/libil2cpp.so` — a 4-byte
 * write over a 32-byte-verified window. No companion `.so`, no DEX change, no
 * Play Billing involvement of any kind.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `CommonHelper.BuyItem(ProductIDType)` — RVA **`0x1E8CE30`**, file **`0x1E88E30`**
 * (file = RVA − 0x4000; the arm64 executable LOAD starts at file offset `0x15048f4`
 * but at vaddr `0x15088f4`, a 16 KiB alignment gap) — is turned into a tail branch
 * straight to `CommonHelper.CompleteIAP(ProductIDType)` at RVA `0x1E8C178`.
 *
 * ```
 * 32-byte verify window, exactly 1 hit in the 61,223,136 B lib:
 *   fe0f1ef8 f44f01a9 34df00b0 88ba4d39 f303002a c8000035 60c800f0 00d043f9
 *
 * write 4 B at the window start:
 *   original     fe0f1ef8     str  x30, [sp, #-0x20]!
 *   replacement  d2fcff17     b    0x1e8c178       ; CommonHelper::CompleteIAP
 * ```
 * ⚠️ The 4- and 8-byte windows at this site have **24,078** hits each in this lib, so
 * a short anchor here would corrupt an arbitrary function. The 32-byte window is
 * what makes the site provable; 4 bytes are what need changing. See
 * `../shared/NativeByteEditor.kt` for the engine that enforces `count == 1` and
 * resolves every anchor before writing any byte.
 *
 * The replacement was written into a copy of the real lib and re-disassembled:
 * `b 0x1e8c178`.
 *
 * ============================================================================
 * WHY THIS SITE, AND WHY IT IS SAFE — the crux
 * ============================================================================
 * ```
 * 1e8ce30: str  x30, [sp, #-0x20]!     <-- REPLACED by the branch
 * 1e8ce34: stp  x20, x19, [sp, #0x10]
 * 1e8ce38: adrp x20, …
 * 1e8ce3c: ldrb w8, [x20, #0x36e]
 * 1e8ce40: mov  w19, w0                ; w0 IS the ProductIDType (static method, no `this`)
 * 1e8ce6c: ldr  x0, [x8]               ; PurchaseManager.instance        (unreached now)
 * 1e8ce74: mov  w1, w19
 * 1e8ce84: b    0x1edabb8              ; PurchaseManager::BuyItem        (unreached now)
 * ```
 * 1. **No register marshalling is needed.** `BuyItem` is `public static void`, so
 *    there is no `this`; the callee's only argument arrives in `w0` and `w0` already
 *    holds the `ProductIDType` on entry (proved by `mov w19, w0` at `0x1E8CE40`).
 *    `b` does not clobber `w0`, so `CompleteIAP` receives the right enum.
 * 2. **The frame bookkeeping is symmetric.** The replacement lands on the *prologue
 *    push*, so no frame is pushed — and therefore none is popped either.
 *    `CompleteIAP` pushes and pops its own frame and returns straight to `BuyItem`'s
 *    caller through the untouched `x30`.
 * 3. **Exact signature match.** `CommonHelper.CompleteIAP` is `public static void`
 *    taking exactly `(ProductIDType)`, the same as `BuyItem`. `CompleteIAP` has only
 *    two callers in the whole game (`ProcessPurchase` and the restore lambda) and it
 *    is the **single grant funnel** — its 8-case jump table runs
 *    `IncreaseTotalPoint` for point packs, and cases 8..57 tail into
 *    `CompleteSpecialIAP(idx, lvl, 10 000, 50 000)`, which writes
 *    `g_specialOfferData[idx].isPurchased = TRUE` (the actual grant), adds 50 000
 *    points and calls `DataHelper.UnlockHeroData(10 / 22)`.
 * 4. **The caller is the purchase button.** `UILockerScroll::OnTouchEnded+0x210`
 *    (`0x192BFC8`) — the shop row tap.
 *
 * **Play Billing is bypassed entirely.** Nothing downstream of the branch is
 * reached, so the patch is independent of the DEX, of the Unity catalog order, of
 * `_productsIDList`, of the Play app being installed, and of the network. That also
 * sidesteps the whole "which Java layer does the C# actually talk to" question:
 * `recon.md` assumed the legacy `com.unity3d.services.store` path, but the active
 * path is `GoogleBillingClient` → `com.android.billingclient.api.*` reached via
 * `AndroidJavaObject`, and neither is executed any more.
 *
 * There is also **no verification to defeat**: `PurchaseManager::ProcessPurchase`
 * (RVA `0x1EDAA20`) — the entire Play Billing result handler — contains no
 * signature/receipt check and no network call, and returns
 * `PurchaseProcessingResult.Complete` unconditionally. So a DEX-side forge (the
 * Dead Trigger shape) would have been pure overhead here.
 *
 * ============================================================================
 * ALTERNATIVE TAIL-CALL SITE — deliberately NOT used
 * ============================================================================
 * There is a second, equally valid site at RVA `0x1E8CE84` / file `0x1E88E84`:
 * `4d370114` (`b 0x1edabb8`) → `bdfcff17` (`b 0x1e8c178`). There the existing
 * unconditional branch is swapped 1:1 *after* the epilogue already popped the frame
 * (`ldp` / `ldr x30,[sp],#0x20` at `0x1E8CE78`–`0x1E8CE80`), so it too needs no stack
 * fixup. **Do NOT patch both** — that would make every shop tap grant twice. The
 * entry site is used because it is the earliest possible interception: nothing in
 * `BuyItem` runs at all, not even the per-method metadata-init thunk.
 *
 * ============================================================================
 * CAVEAT — read this before reporting a bug
 * ============================================================================
 * This is effectively "**first press of any not-yet-owned offer ⇒ granted**":
 *  * rows already owned are still blocked at the **UI** level, not here — the game
 *    greys them out from `g_specialOfferData[idx].isPurchased`, and re-buying an
 *    owned offer is a no-op in the game's own logic. Only two read sites of that
 *    flag exist (`0x1921768`, `0x193AFFC`) and both are purely visual, so there is no
 *    purchase gate left to bypass;
 *  * shop rows that gate on `CommonHelper.IsEnoughPoint` still show affordability
 *    from the real balance — pair this patch with
 *    `../currency/UnlimitedPointsPatch.kt` if you want those rows green too.
 * The two patches are independent and touch disjoint offsets (`0x1E88E30` vs
 * `0x1C10518` / `0x119A8D0`), so enabling both is safe and order-independent.
 *
 * ============================================================================
 * armv7 — NOT COVERED
 * ============================================================================
 * `dump.cs` RVAs are the arm64 build only. The armv7 `libil2cpp.so` is a separate
 * compilation with entirely different addresses and its `CommonHelper::BuyItem` RVA
 * has **not** been derived ([UNVERIFIED] in the target survey). Guessing it and
 * writing over it would be exactly the corruption this engine exists to prevent, so
 * this patch deliberately targets **arm64 only** and leaves the armv7 lib untouched.
 * Both ABIs must therefore be kept in the install unless `--striplibs arm64-v8a` is
 * used. N1's armv7 twin, by contrast, *is* pinned and verified.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * Top-level `rawResourcePatch` val, `default = true`, independently selectable —
 * unlike Dead Trigger's native half, which had to be an `internal object`
 * instance-method builder wired in with `dependsOn(...)` because its two halves are
 * mutually required and the app must expose only ONE listing entry. N2 has no
 * mandatory partner.
 *
 * A `rawResourcePatch` anywhere in the graph forces `ResourceMode.RAW_ONLY`, which is
 * what makes the raw-extracted `root/lib/arm64-v8a/libil2cpp.so` exist and resolve
 * via `get("lib/arm64-v8a/libil2cpp.so", true)`. `listApkEntries("lib/")` returns
 * **empty** for this app (DEFLATE-compressed libs — the Crossy Road trap) and must
 * not be used.
 *
 * ============================================================================
 * DO NOT
 * ============================================================================
 * * **Never bump `versionCode` (471).** The 419 MB OBB is named
 *   `main.471.com.dnddream.HeadBasketball.obb` — the `471` *is* the versionCode.
 *   Unity resolves the expansion as `%s/main.%d.%s.obb`, so a bump makes the OBB
 *   unfindable and the game unplayable on any device holding the official OBB.
 * * **Never touch `assets/unity_obb_guid`** (`8613441d-1d79-44ca-8c7a-2b4bc78f1475`,
 *   36 B, no trailing newline) — `libunity.so` aborts with *"Application OBB has
 *   mismatching GUID"*.
 * * **Never touch `assets/bin/Data/Managed/Metadata/global-metadata.dat`** — it is
 *   inside the APK, and RVA `0x1E8CE30` means nothing if it does not survive the
 *   round-trip byte-identical.
 * * Keep `READ_EXTERNAL_STORAGE` and `android:installLocation="preferExternal"`; do
 *   not delete `assets/bin/Data/data.unity3d` (750,735 B boot/splash stub).
 * * Do **not** patch the `0x1E8CE84` tail-call site as well — double granting.
 * * Do not stub `CommonHelper::CompleteIAP` itself: it *is* the grant, and an entry
 *   stub would break its 8-case jump table and delete the entire purchase pipeline.
 * * Do not build a DEX billing forge for this app. The active path reads fields
 *   straight off the live `Purchase` handle and never round-trips through JSON, and
 *   no IL2CPP code path reaches the dead `com.unity3d.services.store` classes.
 *
 * Full packaging rule set: `../shared/NativeByteEditor.kt`. Measured anchors:
 * analysis/head-basketball/notes/targets.md §4 (N2) and
 * analysis/head-basketball/notes/possible-patches.md §1, §4.
 */
@Suppress("unused")
val headBasketballFreeStorePatch = rawResourcePatch(
    name = "Free Store",
    description = "Everything in the shop is free. Tap an item and you get it instantly — " +
        "no paying, no waiting, no Google Play involved.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HEAD_BASKETBALL)

    execute {
        applyNativeEdits(
            "Free Store",
            listOf(AbiEdits("arm64-v8a", ARM64_ANCHORS)),
        )
    }
}

// arm64 only — file offset = RVA − 0x4000 (exec LOAD off 0x15048f4 → vaddr 0x15088f4).
// armv7 deliberately absent: its BuyItem RVA is unverified (see the KDoc).
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "CommonHelper.BuyItem(ProductIDType) -> b CommonHelper.CompleteIAP (arm64)",
        rva = 0x1E8CE30,
        fileOffset = 0x1E88E30,
        verifyHex = "fe0f1ef8f44f01a934df00b088ba4d39f303002ac800003560c800f000d043f9",
        // b 0x1e8c178  — CommonHelper::CompleteIAP(ProductIDType), public static void
        replacementHex = "d2fcff17",
    ),
)
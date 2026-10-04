package app.headbasketball.patches.currency

import app.headbasketball.patches.shared.AbiEdits
import app.headbasketball.patches.shared.Constants.COMPATIBILITY_HEAD_BASKETBALL
import app.headbasketball.patches.shared.NativeAnchor
import app.headbasketball.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Head Basketball 4.6.4 · **N1 — Unlimited points.**
 * Unity **2022.3.62f3 / IL2CPP** (`com.dnddream.HeadBasketball`, versionCode 471).
 * Static in-place byte edit of the shipped `libil2cpp.so`, **both ABIs**, no
 * companion `.so`, no runtime hook, no DEX change.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `DataHelper.GetTotalPoint()` → `int` is stubbed to always return
 * `0x3B9AC9FF` = **999,999,999**.
 *
 * ```
 * arm64  RVA 0x1C14518  file 0x1C10518  (file = RVA − 0x4000)
 *        32-byte verify window, exactly 1 hit in the 61,223,136 B lib:
 *          fe0f1ef8 f44f01a9 f4f20090 d3da00d0 883e5039 730a43f9 c8000037 c0da00d0
 *        write 12 B at the window start:
 *          original     fe0f1ef8 f44f01a9 f4f20090
 *                        str x30,[sp,#-0x20]! / stp x20,x19,[sp,#0x10] / adrp x20,…
 *          replacement  e03f9952 4073a772 c0035fd6
 *                        mov w0, #0xc9ff ; movk w0, #0x3b9a, lsl #16 ; ret
 *
 * armv7  RVA 0x119A8D0  file 0x119A8D0  (delta 0 — A32, not Thumb)
 *        24-byte verify window, exactly 1 hit in the 53,216,188 B lib:
 *          d0402de9 90409fe5 04408fe0 0000d4e5 000050e3 0400001a
 *        write 12 B at the window start:
 *          original     d0402de9 90409fe5 04408fe0
 *                        push {r4,r6,r7,lr} / ldr r4,[pc,#0x90] / add r4,pc,r4
 *          replacement  ff090ce3 9a0b43e3 1eff2fe1
 *                        movw r0, #0xc9ff ; movt r0, #0x3b9a ; bx lr
 * ```
 * 12 bytes over a 32-byte (24-byte on armv7) verified window — replacement length is
 * an independent axis from verify length. See `../shared/NativeByteEditor.kt` for why
 * 32 bytes is mandatory on this lib (a 4- or 8-byte window has **24,078** hits) and
 * for the two-phase resolve-then-write engine that guarantees **zero bytes written**
 * if any window mismatches or is not unique.
 *
 * Both replacements were written into a copy of the real libs and re-disassembled:
 * `mov w0,#0xc9ff / movk w0,#0x3b9a,lsl#16 / ret` and
 * `movw r0,#0xc9ff / movt r0,#0x3b9a / bx lr`. The 12-byte stub occurs **0** times in
 * the shipped arm64 lib, so the patch cannot collide with itself or with existing
 * code.
 *
 * ============================================================================
 * WHY THIS SINGLE GETTER IS THE WHOLE CURRENCY SYSTEM
 * ============================================================================
 * The game's one currency ("point") has exactly these operations:
 * ```
 *   get    DataHelper.GetTotalPoint()      arm64 0x1C14518   armv7 0x119A8D0   <-- stubbed
 *   add    DataHelper.IncreaseTotalPoint() arm64 0x1C144A0   armv7 0x119A844
 *   spend  DataHelper.DecreaseTotalPoint() arm64 0x1C145AC
 *   set    DataHelper.SetTotalPoint()      arm64 0x1C0A4C4   armv7 0x1183308
 *   gate   CommonHelper.IsEnoughPoint()    arm64 0x1E73D44   = GetTotalPoint() >= price
 * ```
 * Every shop row, hero, equipment and offer price check funnels through
 * `IsEnoughPoint` → `GetTotalPoint`, so forcing the getter makes **all** of them
 * affordable. `GetTotalPoint` touches exactly one static-field slot
 * (`g_i64TotalPointChecksum` @ `0x18`) and nothing else, so no other code path can
 * read a different balance.
 *
 * **Getter-only, on purpose.** `SetTotalPoint`, `IncreaseTotalPoint`,
 * `DecreaseTotalPoint` and `SaveTotalPoint` are all left untouched, so the save
 * file, the "points collected / points spent" statistics and the spend path keep
 * behaving normally — the balance simply *reads* as 999,999,999. This is the same
 * get-only discipline Dead Trigger's `get_storedValue` warning calls for, and it is
 * why this is a currency *unlock* rather than a currency *corruption*.
 *
 * Stubbing the getter is also strictly better than seeding a save file:
 * `GetTotalPoint` is XOR-obfuscated with `0xD59B129B` and **self-checking** — it
 * refuses to decode unless `g_i64TotalPointChecksum == 0xF7FFCDE3`, so a hand-written
 * save file would have to reproduce both.
 *
 * ============================================================================
 * STACK SAFETY
 * ============================================================================
 * Both stubs overwrite the **prologue**, so the discarded
 * `str x30,[sp,#-0x20]!` / `push {r4,r6,r7,lr}` is never executed. No frame is
 * pushed and none is popped, therefore there is no `sp` imbalance and no link-register
 * leak — `ret` / `bx lr` returns to the untouched caller `x30`/`lr`.
 *
 * ============================================================================
 * DELIVERY — a top-level patch, unlike Dead Trigger's two-halves shape
 * ============================================================================
 * Dead Trigger's native half had to be an `internal object` instance-method builder
 * wired in via `dependsOn(...)`, because its two halves (DEX billing forge + native
 * verifier bypass) are mutually required and the app must expose exactly ONE
 * selectable entry. **N1 has no such partner** — it is self-contained, and N2
 * (`../billing/FreeStorePatch.kt`) is an independent feature that happens to touch a
 * *different* offset of the *same* file. So both ship as ordinary top-level
 * `rawResourcePatch` vals, `default = true`, independently selectable. Running both
 * together is safe: N1 writes `0x1C10518` / `0x119A8D0`, N2 writes `0x1E88E30`, all
 * disjoint.
 *
 * A `rawResourcePatch` anywhere in the graph forces `ResourceMode.RAW_ONLY`, which is
 * what makes the raw-extracted `root/lib/<abi>/libil2cpp.so` exist and be resolvable
 * with `get("lib/<abi>/libil2cpp.so", true)`. Note `listApkEntries("lib/")` returns
 * **empty** for this app (the libs are DEFLATE-compressed, so the listing API sees
 * nothing — the Crossy Road trap) and must not be used.
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
 *   inside the APK, and both RVAs above are meaningless if it does not survive the
 *   round-trip byte-identical.
 * * Keep `READ_EXTERNAL_STORAGE` and `android:installLocation="preferExternal"`; do
 *   not delete `assets/bin/Data/data.unity3d` (750,735 B boot/splash stub).
 * * Do not force `isPurchased` / `isUnlocked` reads to fake ownership. They have only
 *   two read sites and both are **purely visual** — no purchase gate re-checks them,
 *   so forcing them would grey/un-grey rows and unlock nothing.
 * * Do not widen this patch to a bare `ret`: `GetTotalPoint` returns `int`, and a
 *   `ret` would return stale `w0`.
 *
 * Full packaging rule set: `../shared/NativeByteEditor.kt`. Measured anchors:
 * analysis/head-basketball/notes/targets.md §4 (N1) and
 * analysis/head-basketball/notes/possible-patches.md §4.
 */
@Suppress("unused")
val headBasketballUnlimitedPointsPatch = rawResourcePatch(
    name = "Unlimited Points",
    description = "Free coins forever. Your point balance always reads as the maximum, " +
        "so you can afford anything in the shop. You can still spend and save normally.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HEAD_BASKETBALL)

    execute {
        applyNativeEdits(
            "Unlimited Points",
            listOf(
                AbiEdits("arm64-v8a", ARM64_ANCHORS),
                AbiEdits("armeabi-v7a", ARMV7_ANCHORS),
            ),
        )
    }
}

// arm64 — file offset = RVA − 0x4000 (exec LOAD off 0x15048f4 → vaddr 0x15088f4).
private val ARM64_ANCHORS = listOf(
    NativeAnchor(
        label = "DataHelper.GetTotalPoint() -> 999,999,999 (arm64)",
        rva = 0x1C14518,
        fileOffset = 0x1C10518,
        verifyHex = "fe0f1ef8f44f01a9f4f20090d3da00d0883e5039730a43f9c8000037c0da00d0",
        // mov w0, #0xc9ff ; movk w0, #0x3b9a, lsl #16 ; ret   -> 0x3B9AC9FF
        replacementHex = "e03f99524073a772c0035fd6",
    ),
)

// armv7 — A32 (not Thumb), single R E LOAD at offset 0 / vaddr 0 → file offset = RVA.
private val ARMV7_ANCHORS = listOf(
    NativeAnchor(
        label = "DataHelper.GetTotalPoint() -> 999,999,999 (armv7)",
        rva = 0x119A8D0,
        fileOffset = 0x119A8D0,
        verifyHex = "d0402de990409fe504408fe00000d4e5000050e30400001a",
        // movw r0, #0xc9ff ; movt r0, #0x3b9a ; bx lr         -> 0x3B9AC9FF
        replacementHex = "ff090ce39a0b43e31eff2fe1",
    ),
)
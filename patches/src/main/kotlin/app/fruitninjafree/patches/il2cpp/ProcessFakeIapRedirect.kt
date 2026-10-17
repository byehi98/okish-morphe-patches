package app.fruitninjafree.patches.il2cpp

import app.fruitninjafree.patches.shared.AbiEdits
import app.fruitninjafree.patches.shared.Constants.COMPATIBILITY_FRUIT_NINJA_FREE
import app.fruitninjafree.patches.shared.NativeAnchor
import app.fruitninjafree.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.RawResourcePatch
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Fruit Ninja Free 3.97.9 — **P2: `ProcessGooglePlayIAP` → `ProcessFakeIAP`
 * redirect** (IL2CPP, static `lib/<abi>/libil2cpp.so` edit — one byte site per
 * ABI: arm64-v8a AND armeabi-v7a, each measured against its own shipped lib).
 *
 * Native half of `../billing/FruitNinjaFreeIapPatch.kt` — NOT a standalone
 * patch: [ProcessFakeIapRedirect.patch] builds it and the billing patch attaches
 * it via `dependsOn(...)`, so the app exposes exactly ONE selectable entry
 * ("Fruit Ninja Free IAP") while a run applies BOTH halves. It follows the
 * Dead Trigger `NativeIapVerifierBypass` shape: the builder is an INSTANCE
 * method of this `internal object`, which PatchLoader's discovery (public
 * static Patch-valued fields/methods) never sees.
 *
 * ============================================================================
 * WHY THIS EXISTS — PlayFab rejects every synthetic receipt (device-proven)
 * ============================================================================
 * Without this half, a purchase delivered by the Java billing hook travels the
 * app's real validation path:
 * `PurchaseManager.ProcessPurchase → ValidateAndProcessIAP →
 * ProcessGooglePlayIAP → PlayFab ClientAPI.ValidateGooglePlayPurchase` — the
 * **server** verifies the Google `purchaseToken` against the Play Developer
 * API, rejects the fake one, and the store shows "signature invalid"
 * (`analysis/com.halfbrick.fruitninjafree/notes/iap-validation-debug.md`).
 * No Java-side adjustment can pass that check — but the game itself contains a
 * second, *client-callable* grant path: `PurchaseManager.ProcessFakeIAP`
 * (RVA 0x1CEEC7C), which posts the game's own `GrantItem` request to the
 * Halfbrick/PlayFab backend and funnels the result into the *same*
 * `ProcessPurchasedItems → FinalizePurchase` chain a validated purchase ends
 * in. It is dead code in 3.97.9 (zero refs re-verified this session), but it is
 * redirect-**enterable**: both candidates ignore `this`.
 *
 * ============================================================================
 * WHAT IS PATCHED (arm64-v8a) — one 12-byte prologue replacement
 * ============================================================================
 * `PurchaseManager.ProcessGooglePlayIAP(PurchaseData, Action)` (dump.cs RVA
 * 0x1CEE874 / Offset 0x1CEA874) gets its prologue replaced with a register
 * shuffle + tail branch into `ProcessFakeIAP`:
 * ```
 * RVA 0x1CEE874  file 0x1CEA874  (file = RVA − 0x4000, re-verified)
 *   original   fe0f1cf8 f85f01a9 f65702a9     str x30,[sp,#-0x40]! ; stp x24,x23,[sp,#0x10] ; stp x22,x21,[sp,#0x20]
 *   replacement e30302aa e2031faa 00010014    mov x3,x2 ; mov x2,xzr ; b 0x1CEEC7C
 * ```
 * At the only call site (tail `b` from `ValidateAndProcessIAP`'s
 * "Store == GooglePlay" branch, RVA 0x1CEC604) the incoming registers are
 * `x1 = PurchaseData`, `x2 = onComplete` (`mov x1,x20; mov x2,x19` two
 * instructions before the branch — objdump-verified). `ProcessFakeIAP`'s
 * signature is `(PurchaseData data, string source, Action onComplete)`, so:
 * * `x3 ← x2` — onComplete slides into arg3 (x1 already = data);
 * * `x2 ← null` — `source` (an analytics field at request+0x30). The original
 *   callers passed "Fake"/"DebugMenu"; a real string would need a cave +
 *   static-string load, so null ships first and is a documented device-test
 *   risk (see RISKS below);
 * * `b ProcessFakeIAP` — tail call. `imm26 = (0x1CEEC7C − 0x1CEE87C)/4 =
 *   0x100` → word `14000100`. The frame unwind is consistent: the caller
 *   already popped its own frame before the original tail-b, and
 *   ProcessFakeIAP builds/returns through its own prologue/epilogue exactly
 *   like ProcessGooglePlayIAP would have.
 *
 * Crash-safety re-verified against the full body (0x1CEEC7C–0x1CEEDD4
 * disassembled): `x0` (this) is **never read** (its first use is the
 * static-init `adrp` that overwrites it), so the redirect works from any
 * caller; null `data`/product chain hits `cbz → 0x1CEEDD8` (the il2cpp
 * nullref stub) rather than corrupting state; the success callback
 * `<ProcessFakeIAP>b__0` tail-branches into `ProcessPurchasedItems` (0x1CEC718)
 * → `FinalizePurchase` — the real grant path.
 *
 * ============================================================================
 * ANCHOR WINDOW (arm64-v8a) — the 12 B prologue alone is NOT unique (correction)
 * ============================================================================
 * Whole-file measurement against the shipped 71,750,584 B arm64 lib
 * (re-measured this session; see notes `native-freeiap-round2.md` round-3
 * corrections):
 * ```
 *   12 B prologue (str x30 / stp / stp — a standard il2cpp frame)  -> 7,481 hits
 *   12 B + 4 B / + 8 B of the same prologue                        -> 7,481 / 2 hits
 *   12 B + 12 B (adds stp x20,x19 + the two site-specific adrp)    ->     1 hit ✓
 * ```
 * So the engine's uniqueness axis uses a **24 B verify window** (the original
 * 12 B PLUS the 12 B that follow) over which exactly-one-hit is enforced at
 * patch time; the replacement is still only the first 12 B (the last 12 B of
 * the window are never written). Zero bytes are written on a window mismatch
 * or a non-unique hit — see `../shared/NativeByteEditor.kt`.
 *
 * ⚠️ **NOTE vs round-2 §4.3**: the notes' replacement byte string
 * `aa0302e3 aa1f03f2 140000fc` is garbled (it does not decode — capstone
 * refuses it — and its branch immediate was computed from PC 0x1CEE88C, seven
 * slots past the real site). The bytes written here were re-derived from the
 * intended instruction semantics (`mov x3,x2 ; mov x2,xzr ; b ProcessFakeIAP`)
 * and independently decoded back to exactly those three instructions at
 * RVA 0x1CEE874 before shipping. The notes' *semantics* are correct; only the
 * hex encodings were wrong.
 *
 * ============================================================================
 * ENTRY-REF SCAN — why overwriting the prologue is safe
 * ============================================================================
 * Every PC-relative form (b/bl, b.cond, cbz/cbnz, tbz/tbnz, adr, adrp) was
 * scanned across the whole lib this session:
 * * `ProcessGooglePlayIAP` (0x1CEE874): exactly **one** ref — the tail `b`
 *   at 0x1CEC604 (the GooglePlay dispatch in `ValidateAndProcessIAP`).
 *   Three `cbz`s target 0x1CEE870 — the null-throw stub `bl` immediately
 *   BEFORE the entry — which this patch does not touch.
 * * `ProcessFakeIAP` (0x1CEEC7C): **zero** refs (dead code, redirect target).
 *
 * ============================================================================
 * armeabi-v7a TWIN — same redirect, A32 bytes, delta-0 offsets
 * ============================================================================
 * The armeabi-v7a lib (61,685,188 B, ELF 32-bit ARM EABI5, **A32/ARM-state
 * code, NOT Thumb**, stripped, BuildID 745ad03b…) is a SEPARATE native build:
 * its method RVAs must never be extrapolated from the arm64 dump (fresh v7a
 * Il2CppDumper run: `ProcessGooglePlayIAP` = **0xF96C4C**, `ProcessFakeIAP` =
 * **0xF97178**), and its RX LOAD maps vaddr 0x0 ↔ file offset 0x0, so
 * **file offset = RVA (delta 0)** — unlike arm64's −0x4000. Same 12-byte
 * register shuffle + tail branch, A32-encoded (`readelf -lW` + llvm-mc/objdump
 * verified):
 * ```
 * RVA 0xF96C4C  file 0xF96C4C  (file = RVA, delta 0)
 *   original   e92d4ff0 e24dd014 e59f52c4    push {r4-r11,lr} ; sub sp,#0x14 ; ldr r5,[pc,#0x2c4]
 *   replacement e1a03002 e3a02000 ea000147    mov r3,r2 ; mov r2,#0 ; b 0xF97178
 * ```
 * Register state at the sole call site (tail `b` from `ValidateAndProcessIAP`'s
 * GooglePlay branch at file 0xF94378): `r1 = data`, `r2 = onComplete` (r0 is a
 * store-check result the callee never reads — same as arm64). Same shuffle as
 * arm64: `r3 ← r2` (onComplete → arg4), `r2 ← null` (source). Branch imm24 =
 * (0xF97178 − 0xF96C5C)/4 = 0x147 → word `ea000147`. Ref topology identical to
 * arm64 (exactly one tail-b into ProcessGooglePlayIAP; ProcessFakeIAP has zero
 * refs), and v7a `ProcessFakeIAP` also never reads incoming r0. Whole-file
 * re-verification against the shipped lib inside
 * `apk/FruitNinja_3.97.9_APKPure.xapk!/config.armeabi_v7a.apk!/lib/armeabi-v7a/libil2cpp.so`:
 * the 24 B window occurs **exactly once** at file 0xF96C4C; the 12 B
 * replacement occurs **zero** times pre-patch. Full derivation:
 * `analysis/com.halfbrick.fruitninjafree/notes/native-v7a-targets.md`.
 *
 * ============================================================================
 * RISKS & UNKNOWNs (ordered — device test decides)
 * ============================================================================
 * 1. **Server acceptance of the fake-IAP endpoint is untested.** The
 *    `GrantItem` request this path posts was live in QA (the debug menu's
 *    token buttons use the same endpoint), but production may gate it. If the
 *    backend refuses, the tap ends in the game's normal IAP-error UI — no
 *    crash, no grant.
 * 2. **`source = null`** lands in the request's analytics field (request+0x30).
 *    If the server validates it, the grant fails the same non-crashing way.
 *    (Fallback if both 1 and 2 fail: the round-2 §3 P3 debug-menu patch —
 *    manual self-serve grants through the game's own UI.)
 * 3. Still requires the Java half: this redirect changes *how* a delivered
 *    purchase is granted, not *whether* one fires. The two halves are
 *    mutually required — see the billing patch's KDoc.
 *
 * ============================================================================
 * DELIVERY / ABI
 * ============================================================================
 * Top-level `rawResourcePatch` built only by [patch] (never discovered —
 * invisible in list-patches). A `rawResourcePatch` anywhere in the graph forces
 * `ResourceMode.RAW_ONLY`, which is what makes the raw-extracted
 * `root/lib/<abi>/libil2cpp.so` resolvable via `get(...)`. BOTH shipped ABIs
 * are patched, each from its own measured byte site (arm64 above, v7a twin
 * section). Per-ABI semantics at run time (see
 * `../shared/NativeByteEditor.kt` "ABI HANDLING"): an ABI whose lib is absent
 * from the APK is skipped with a log (e.g. an arm64-only APKMirror split, or
 * `--striplibs`), a present-but-different-build lib is a hard verify error,
 * and the patch errors only when NONE of the targeted ABIs is present. On the
 * APKPure XAPK the v7a lib lives in the `config.armeabi_v7a.apk` split; on
 * APKMirror both config splits are present, so both halves apply.
 *
 * Byte sites: `analysis/com.halfbrick.fruitninjafree/notes/native-freeiap-round2.md`
 * §4.3 (arm64; + round-3 verification corrections appended to that file),
 * verified against `analysis/com.halfbrick.fruitninjafree/il2cpp/libil2cpp.so`
 * (71,750,584 B, BuildID 8b779e15…) — and
 * `analysis/com.halfbrick.fruitninjafree/notes/native-v7a-targets.md`
 * (armeabi-v7a twin), verified against the lib inside the shipped XAPK's
 * `config.armeabi_v7a.apk` split (61,685,188 B, BuildID 745ad03b…).
 */
internal object ProcessFakeIapRedirect {

    /**
     * Builds the native half of Fruit Ninja Free IAP — called exactly once,
     * from `../billing/FruitNinjaFreeIapPatch.kt`'s `dependsOn(…)` argument.
     *
     * MUST stay a member of this object (an instance method): PatchLoader only
     * discovers `public static` Patch-valued fields/methods, so this builder is
     * invisible to listing while remaining fully executable as a dependency.
     * Do NOT convert it to a top-level `val`/`fun` — that would register the
     * native half as a SECOND selectable patch.
     */
    fun patch(): RawResourcePatch = rawResourcePatch(
        name = "Free IAP (native fake-IAP redirect)",
        description = "Native half of Free IAP: redirects the " +
            "game's Google-Play purchase handler to its built-in fake-IAP " +
            "grant path so a tapped store item is granted by the game's own " +
            "backend without receipt validation. Runs automatically with " +
            "Free IAP; not separately selectable.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_FRUIT_NINJA_FREE)

        execute {
            applyNativeEdits(
                "Free IAP",
                listOf(
                    AbiEdits("arm64-v8a", listOf(PROCESS_GOOGLE_PLAY_IAP_REDIRECT)),
                    AbiEdits("armeabi-v7a", listOf(PROCESS_GOOGLE_PLAY_IAP_REDIRECT_V7A)),
                ),
            )
        }
    }
}

// arm64-v8a — file offset = RVA − 0x4000 (RX LOAD: vaddr 0x18BB3B4 ↔ offset 0x18B73B4).
//
// 24 B verify window (unique; measured whole-file) / 12 B replacement:
//   RVA 0x1CEE874 … +0x18   file 0x1CEA874
//     str x30,[sp,#-0x40]! ; stp x24,x23,[sp,#0x10] ; stp x22,x21,[sp,#0x20]
//     stp x20,x19,[sp,#0x30] ; adrp x20,… ; adrp x22,…
//   write the first 3 instructions only:
//     e30302aa e2031faa 00010014   =  mov x3,x2 ; mov x2,xzr ; b 0x1CEEC7C
private val PROCESS_GOOGLE_PLAY_IAP_REDIRECT = NativeAnchor(
    label = "PurchaseManager.ProcessGooglePlayIAP entry — mov x3,x2; mov x2,xzr; b ProcessFakeIAP",
    rva = 0x1CEE874,
    fileOffset = 0x1CEA874,
    verifyHex = "fe0f1cf8f85f01a9f65702a9f44f03a9543c01f076220190",
    replacementHex = "e30302aae2031faa00010014",
)

// armeabi-v7a — file offset = RVA (RX LOAD: vaddr 0x0 ↔ offset 0x0, delta 0 —
// DIFFERENT from arm64's −0x4000; the two ABIs are separate builds, see KDoc).
// A32 (ARM-state), NOT Thumb. Whole-file re-verified this session against the
// lib inside the shipped XAPK's config.armeabi_v7a.apk split: window exactly
// 1 hit at 0xF96C4C, replacement 0 hits pre-patch.
//
// 24 B verify window (unique; measured whole-file) / 12 B replacement:
//   RVA 0xF96C4C … +0x18   file 0xF96C4C
//     push {r4-r11,lr} ; sub sp,#0x14 ; ldr r5,[pc,#0x2c4]
//     mov r6,r2 ; mov r4,r1 ; add r5,pc,r5
//   write the first 3 instructions only:
//     e1a03002 e3a02000 ea000147   =  mov r3,r2 ; mov r2,#0 ; b 0xF97178
private val PROCESS_GOOGLE_PLAY_IAP_REDIRECT_V7A = NativeAnchor(
    label = "PurchaseManager.ProcessGooglePlayIAP entry — mov r3,r2; mov r2,#0; b ProcessFakeIAP (A32)",
    rva = 0xF96C4C,
    fileOffset = 0xF96C4C,
    verifyHex = "f04f2de914d04de2c4529fe50260a0e10140a0e105508fe0",
    replacementHex = "0230a0e10020a0e3470100ea",
)

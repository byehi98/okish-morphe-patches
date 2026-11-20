package app.fruitninjafree.patches.il2cpp

import app.fruitninjafree.patches.shared.AbiEdits
import app.fruitninjafree.patches.shared.Constants.COMPATIBILITY_FRUIT_NINJA_FREE
import app.fruitninjafree.patches.shared.NativeAnchor
import app.fruitninjafree.patches.shared.applyNativeEdits
import app.morphe.patcher.patch.RawResourcePatch
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Fruit Ninja Free 3.98.4 (versionCode 90061600) — **P2:
 * `ProcessGooglePlayIAP` → `ProcessFakeIAP` redirect** (IL2CPP, static
 * `lib/<abi>/libil2cpp.so` edit — one byte site per ABI: arm64-v8a AND
 * armeabi-v7a, each measured against its own shipped lib).
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
 * (RVA 0x1D0738C), which posts the game's own `GrantItem` request to the
 * Halfbrick/PlayFab backend and funnels the result into the *same*
 * `ProcessPurchasedItems → FinalizePurchase` chain a validated purchase ends
 * in. It is dead code in 3.98.4 (zero refs re-verified for this build), but it is
 * redirect-**enterable**: both candidates ignore `this`.
 *
 * ============================================================================
 * WHAT IS PATCHED (arm64-v8a) — one 12-byte prologue replacement
 * ============================================================================
 * `PurchaseManager.ProcessGooglePlayIAP(PurchaseData, Action)` (dump.cs RVA
 * 0x1D06F84 / Offset 0x1D02F84) gets its prologue replaced with a register
 * shuffle + tail branch into `ProcessFakeIAP`:
 * ```
 * RVA 0x1D06F84  file 0x1D02F84  (file = RVA − 0x4000, re-verified)
 *   original   fe0f1cf8 f85f01a9 f65702a9     str x30,[sp,#-0x40]! ; stp x24,x23,[sp,#0x10] ; stp x22,x21,[sp,#0x20]
 *   replacement e30302aa e2031faa 00010014    mov x3,x2 ; mov x2,xzr ; b 0x1D0738C
 * ```
 * At the only call site (tail `b` from `ValidateAndProcessIAP`'s
 * "Store == GooglePlay" branch, RVA 0x1D04D14) the incoming registers are
 * `x1 = PurchaseData`, `x2 = onComplete` (`mov x1,x20; mov x2,x19` at
 * 0x1D04D04/0x1D04D08 — capstone-verified). `ProcessFakeIAP`'s
 * signature is `(PurchaseData data, string source, Action onComplete)`, so:
 * * `x3 ← x2` — onComplete slides into arg3 (x1 already = data);
 * * `x2 ← null` — `source` (an analytics field at request+0x30). The original
 *   callers passed "Fake"/"DebugMenu"; a real string would need a cave +
 *   static-string load, so null ships first and is a documented device-test
 *   risk (see RISKS below);
 * * `b ProcessFakeIAP` — tail call. `imm26 = (0x1D0738C − 0x1D06F8C)/4 =
 *   0x100` → word `14000100`. The frame unwind is consistent: the caller
 *   already popped its own frame before the original tail-b, and
 *   ProcessFakeIAP builds/returns through its own prologue/epilogue exactly
 *   like ProcessGooglePlayIAP would have.
 *
 * Note: 3.98.4's `ValidateAndProcessIAP` also tail-branches to
 * `ProcessIOSIAP` (RVA 0x1D071FC) from 0x1D04DA4 for the other store branch.
 * That branch is a DIFFERENT target and is untouched; the ProcessGooglePlayIAP
 * ref count remains exactly one.
 *
 * Crash-safety re-verified against the full body (0x1D0738C onwards,
 * disassembled): `x0` (this) is **never read** (its first use is the
 * static-init `adrp` that overwrites it), so the redirect works from any
 * caller; null `data`/product chain hits `cbz → 0x1D074E8` (the il2cpp
 * nullref stub) rather than corrupting state; the success callback
 * `<ProcessFakeIAP>b__0` (RVA 0x1D08F48) tail-branches into
 * `ProcessPurchasedItems` (0x1D04E28) → `FinalizePurchase` (0x1D057B8)
 * — the real grant path.
 *
 * ============================================================================
 * ANCHOR WINDOW (arm64-v8a) — the 12 B prologue alone is NOT unique
 * ============================================================================
 * Whole-file measurement against the shipped 72,040,112 B arm64 lib of
 * 3.98.4 (re-measured for this build; the whole `0x1C00000-0x1E00000` code region
 * is byte-identical to 89975500, so the anchor offsets and branch immediates
 * carried over unchanged and were re-proven unique whole-file):
 * ```
 *   12 B prologue (str x30 / stp / stp — a standard il2cpp frame)  -> 7,510 hits
 *   12 B + 12 B (adds stp x20,x19 + the two site-specific adrp)    ->     1 hit ✓
 * ```
 * So the engine's uniqueness axis uses a **24 B verify window** (the original
 * 12 B PLUS the 12 B that follow) over which exactly-one-hit is enforced at
 * patch time; the replacement is still only the first 12 B (the last 12 B of
 * the window are never written). Zero bytes are written on a window mismatch
 * or a non-unique hit — see `../shared/NativeByteEditor.kt`.
 *
 * The 24 B window (identical in 3.98.3 and 3.98.4):
 * `fe0f1cf8 f85f01a9 f65702a9 f44f03a9 d43d01b0 d6230190`
 *
 * ⚠️ **Historical note (3.97.9 round-2 §4.3)**: the old notes' replacement
 * byte string `aa0302e3 aa1f03f2 140000fc` was garbled (it does not decode —
 * capstone refuses it — and its branch immediate was computed from the wrong
 * PC). The bytes written here are derived from the intended instruction
 * semantics (`mov x3,x2 ; mov x2,xzr ; b ProcessFakeIAP`) and independently
 * decoded back to exactly those three instructions at RVA 0x1D06F84.
 *
 * ============================================================================
 * ENTRY-REF SCAN — why overwriting the prologue is safe
 * ============================================================================
 * Every PC-relative form (b/bl, b.cond, cbz/cbnz, tbz/tbnz, adr, adrp) was
 * scanned across the whole lib this session:
 * * `ProcessGooglePlayIAP` (0x1D06F84): exactly **one** ref — the tail `b`
 *   at 0x1D04D14 (the GooglePlay dispatch in `ValidateAndProcessIAP`).
 * * `ProcessFakeIAP` (0x1D0738C): **zero** refs (dead code, redirect target).
 * * `ProcessIOSIAP` (0x1D071FC): one ref (0x1D04DA4) — a different target that
 *   this patch never touches.
 *
 * ============================================================================
 * armeabi-v7a TWIN — same redirect, A32 bytes, delta-0 offsets
 * ============================================================================
 * The armeabi-v7a lib (61,960,084 B, ELF 32-bit ARM EABI5, **A32/ARM-state
 * code, NOT Thumb**, stripped) is a SEPARATE native build:
 * its method RVAs must never be extrapolated from the arm64 dump (fresh v7a
 * Il2CppDumper run: `ProcessGooglePlayIAP` = **0xFA57BC**, `ProcessFakeIAP` =
 * **0xFA5CE8**), and its RX LOAD maps vaddr 0x0 ↔ file offset 0x0, so
 * **file offset = RVA (delta 0)** — unlike arm64's −0x4000. Same 12-byte
 * register shuffle + tail branch, A32-encoded (capstone-verified):
 * ```
 * RVA 0xFA57BC  file 0xFA57BC  (file = RVA, delta 0)
 *   original   e92d4ff0 e24dd014 e59f52c4    push {r4-r11,lr} ; sub sp,#0x14 ; ldr r5,[pc,#0x2c4]
 *   replacement e1a03002 e3a02000 ea000147    mov r3,r2 ; mov r2,#0 ; b 0xFA5CE8
 * ```
 * Register state at the sole call site (tail `b` from `ValidateAndProcessIAP`'s
 * GooglePlay branch at file 0xFA2EE8): `r1 = data`, `r2 = onComplete`
 * (`mov r1,r5 ; mov r2,r4` immediately before the branch — capstone-verified;
 * r0 is a store-check result the callee never reads — same as arm64). Same
 * shuffle as arm64: `r3 ← r2` (onComplete → arg4), `r2 ← null` (source).
 * Branch imm24 = (0xFA5CE8 − 0xFA57CC)/4 = 0x147 → word `ea000147`
 * (A32 PC = instruction address + 8). Ref topology identical to arm64 (exactly
 * one tail-b into ProcessGooglePlayIAP; ProcessFakeIAP has zero refs), and v7a
 * `ProcessFakeIAP` also never reads incoming r0. Whole-file re-verification
 * against the shipped lib inside 3.98.4's `split_config.armeabi_v7a.apk`:
 * the 24 B window occurs **exactly once** at file 0xFA57BC; the 12 B
 * replacement occurs **zero** times pre-patch.
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
 * from the APK is skipped with a log (e.g. an arm64-only split, or
 * `--striplibs`), a present-but-different-build lib is a hard verify error,
 * and the patch errors only when NONE of the targeted ABIs is present. The
 * 3.98.4 APKMirror `.apkm` ships BOTH `split_config.arm64_v8a.apk` and
 * `split_config.armeabi_v7a.apk`, so both halves apply from one bundle.
 *
 * Byte sites were derived for 89975500 from Il2CppDumper runs
 * (metadata v31) against each ABI's shipped `libil2cpp.so` + the shared
 * `global-metadata.dat`, and verified byte-exact with a whole-file scan.
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
                    AbiEdits(
                        "arm64-v8a",
                        listOf(
                            // build 90061600 (3.98.4 — the only supported build)
                            listOf(ARM64_ANCHOR_90061600),
                        ),
                    ),
                    AbiEdits(
                        "armeabi-v7a",
                        listOf(
                            // build 90061600 (3.98.4 — the only supported build)
                            listOf(V7A_ANCHOR_90061600),
                        ),
                    ),
                ),
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Build-aware native anchors — Fruit Ninja Free 3.98.4 (versionCode 90061600).
//
// The redirect bytes are the same instruction sequence on both ABIs (mov x3,x2 ;
// mov x2,xzr ; b ProcessFakeIAP on arm64, the A32 twin on v7a); only the method
// RVAs differ per build. Each anchor below is measured against the shipped lib of
// THIS build and its 24 B verify window occurs exactly once in that lib (verified
// via a whole-file scan). The replacement bytes occur zero times pre-patch.
//
// Measured against the APKMirror 3.98.4 bundle (lib sizes and the entire
// 0x1C00000-0x1E00000 / 0xF00000-0x1000000 code regions are byte-identical to
// 89975500 — the 3.98.4 libs differ only in metadata/data and the build-id, so
// these byte sites and branch targets remain exact):
//   arm64 lib: 72,040,112 B, file = RVA - 0x4000
//   v7a   lib: 61,960,084 B, file = RVA
// Il2CppDumper (metadata v31) on the matching ABI lib + the shared
// global-metadata.dat:
//   arm64  ProcessGooglePlayIAP RVA 0x1D06F84 / Offset 0x1D02F84
//          ProcessFakeIAP       RVA 0x1D0738C
//   v7a    ProcessGooglePlayIAP RVA 0xFA57BC / Offset 0xFA57BC
//          ProcessFakeIAP       RVA 0xFA5CE8
// ---------------------------------------------------------------------------

private const val ARM64_REPLACEMENT = "e30302aae2031faa00010014"
private const val V7A_REPLACEMENT = "0230a0e10020a0e3470100ea"

private const val ARM64_VERIFY_90061600 = "fe0f1cf8f85f01a9f65702a9f44f03a9d43d01b0d6230190"
private const val V7A_VERIFY_90061600 = "f04f2de914d04de2c4529fe50260a0e10140a0e105508fe0"

private val ARM64_ANCHOR_90061600 = NativeAnchor(
    label = "PurchaseManager.ProcessGooglePlayIAP entry — mov x3,x2; mov x2,xzr; b ProcessFakeIAP (build 90061600)",
    rva = 0x1D06F84,
    fileOffset = 0x1D02F84,
    verifyHex = ARM64_VERIFY_90061600,
    replacementHex = ARM64_REPLACEMENT,
)

private val V7A_ANCHOR_90061600 = NativeAnchor(
    label = "PurchaseManager.ProcessGooglePlayIAP entry — mov r3,r2; mov r2,#0; b ProcessFakeIAP (A32, build 90061600)",
    rva = 0xFA57BC,
    fileOffset = 0xFA57BC,
    verifyHex = V7A_VERIFY_90061600,
    replacementHex = V7A_REPLACEMENT,
)

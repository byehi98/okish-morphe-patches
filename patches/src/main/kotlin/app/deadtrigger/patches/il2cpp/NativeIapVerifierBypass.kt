package app.deadtrigger.patches.il2cpp

import app.deadtrigger.patches.shared.Constants.COMPATIBILITY_DEAD_TRIGGER
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.RawResourcePatch
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile

/**
 * Dead Trigger 2.3.4 — Native IAP verifier bypass (IL2CPP, static libil2cpp.so patch,
 * BOTH ABIs). Native half of `../billing/FreeStorePatch.kt` — NOT a standalone patch:
 * [NativeIapVerifierBypass.patch] builds it and FreeStore attaches it via
 * `dependsOn(NativeIapVerifierBypass.patch())`, so the app exposes exactly ONE
 * selectable entry ("Dead Trigger Free Store") while a run applies BOTH halves.
 *
 * ============================================================================
 * WHY THIS EXISTS — the grant dies natively (device-proven)
 * ============================================================================
 * The Java half ("Dead Trigger Free Store") successfully fabricates a `Purchase`
 * (signature literal `"morphe-signature"`) and hands it to C#. The C# side then runs
 * a chain that ends in a **fully local, in-process RSA-SHA1 check** — no server, no
 * network anywhere in purchase/verify/grant (disasm-verified end-to-end, see
 * analysis/deadtrigger/notes/native-iap-verifier.md):
 *
 *   button → IAPRequestPurchase → d__79 grant gate → IAP d__18 →
 *   Billing.Buy_Internal d__33 → Billing.ConfirmTransaction d__37 →
 *   IAPVerifier d__2 (PlayerPrefs "IAPTRANSACTIONS" dedupe) →
 *   IAPVerifier d__5 (Convert.FromBase64String("morphe-signature") throws
 *   FormatException → catch → callback(CantVerify=0)) →
 *   d__37 → d__33 maps non-Accepted → EBuyState.Failure(2) →
 *   m_LastBuyResult=Failure → d__79 never calls IAPProcessBoughtItem → GRANT DROPPED.
 *
 * Observed logcat: `Verify transaction done: CantVerify` → `Buy end Failure`.
 * We cannot forge Google's signature, so the verdict itself is forced natively.
 *
 * There is no cleaner swap available: `IAP.<Initialize_Coroutine>d__16` unconditionally
 * does `new IAPVerifier()` (bl @ RVA 0x15ED51C, method starts 0x15ECF6C) and
 * `IAP.IAPVerifier` is the ONLY `IProcessor` implementation in the whole metadata —
 * the "force the built-in Dummy processor" idea in FreeStorePatch's original KDoc is
 * invalid (no `Dummy : IProcessor` exists).
 *
 * ============================================================================
 * WHAT IS PATCHED — Option A (primary) + Option B (restore coverage)
 * ============================================================================
 * Five 4-byte instruction replacements total: 3 anchors on arm64, 2 on armv7,
 * all inside `Madfinger.Plugins.Billing.Billing` state machines, all *downstream* of
 * the verifier's own state (dedupe-safe, see below). Byte tables re-verified against
 * the shipped .so files + capstone before writing (file offsets below).
 *
 * Option A — force `EBuyState = Success(1)` in `Buy_Internal d__33` state3 ternary
 *   arm64  RVA 0x14AB688 (file 0x14A7688 = RVA − 0x4000)
 *          `cinc w1, w9, ne` (21 05 89 1A) → `mov w1, #1` (21 00 80 52)
 *          Context verified: `mov w9, #1` @0x14A7674 … `cmp w10, #1` … cinc … `blr x10`
 *          ⇒ original yields w1 = accepted ? 1 : 2 (the Success/Failure ternary);
 *          replacement forces Success unconditionally right before the continuation
 *          that writes m_LastBuyResult. w1 is the only register touched; flags dead.
 *   armv7  RVA 0xA52544 = file offset (A32, first LOAD R E at vaddr 0)
 *          `movwne r5, #2` (02 50 00 13) → `mov r5, #1` (01 50 A0 E3)
 *          Context verified: jump-table state3 target 0xA52524 (raw table @0xA523B4,
 *          entry 0x170 → 0xA523B4+0x170+0x10), `ldr r5,[r4,#0x14]; cmp r5,#1` …
 *          `b 0xA52620` … `mov r1, r5` @0xA52634 … `blx` ⇒ r1 = EBuyState.
 *
 *   A does NOT cover the restore/acknowledge `ConfirmTransaction` call sites
 *   (arm64 0x14AC58C / 0x14AD204 — both re-verified: `bl` target = the
 *   `Billing$$ConfirmTransaction` wrapper @ RVA 0x14AAEF4), which bypass
 *   Buy_Internal state3 entirely.
 *
 * Option B — force `EConfirmState = Accepted(1)` at the `ConfirmTransaction d__37`
 *   callback, covering buy + restore + ProcessPendingTransactions in one place:
 *   arm64  RVA 0x14ABAC4 (file 0x14A7AC4): state1 CantVerify branch
 *          `mov w1, wzr` (E1 03 1F 2A) → `mov w1, #1` (21 00 80 52)
 *          (callback(0) terminal block: ldr x9,[x8,#0x18] … mov w1,wzr … blr x9)
 *   arm64  RVA 0x14ABA9C (file 0x14A7A9C): state2 final callback
 *          `ldr w1, [x8, #0x10]` (01 11 40 B9) → `mov w1, #1` (21 00 80 52)
 *          ([x8] is the DisplayClass: state @+0x10 on arm64 = @+0x8 on armv7,
 *          consistent with the il2cpp object header sizes on each ABI)
 *   armv7  RVA 0xA52AD0 = file offset: merged shared callback block
 *          `ldr r4, [r9, #8]` (08 40 99 E5) → `mov r4, #1` (01 40 A0 E3)
 *          (r4 feeds ONLY `mov r1, r4` @0xA52AE4 → `blx r3` @0xA52AF0 — verified
 *          across the full window 0xA52AA0–0xA52AF4; sb is the r9 alias)
 *
 * *** arm64 replacement-byte CORRECTION vs notes/native-iap-verifier.md ***
 * The notes' arm64 replacement `21 00 28 52` is WRONG: capstone decodes
 * 0x52280021 as `eor w1, w1, #0x1000000` — it would XOR the verdict, not force it.
 * The correct encoding of `mov w1, #1` is MOVZ `0x52800021` = `21 00 80 52`
 * (independently decoded back to `mov w1, #1` before use). All three arm64 anchors
 * use the corrected bytes. The armv7 replacements in the notes were byte-verified
 * correct as written.
 *
 * WHY A+B (restore-path decision): FreeStorePatch T3 fabricates the owned `Weekly`
 * subscription, so restore/acknowledge confirms (`IAP d__18` state1 / AlreadyOwned /
 * ProcessPendingTransactions → ConfirmTransaction, bypassing Buy_Internal state3) are
 * actively exercised on this app — A alone leaves them failing with CantVerify. The
 * notes rank both as dedupe-safe and state "both together is harmless" (B makes the
 * buy-path half of A redundant, A stays as the smallest-blast-radius core), so both
 * are applied. The optional armv7 twin `movwne r5, #2` @ RVA 0xA52614 (same word,
 * documented as a *dead* null-DisplayClass fallthrough copy) is deliberately NOT
 * patched — dead code needs no patch, and the jump-table path is 0xA52544.
 *
 * DEDUPE (why repeat purchases keep working): `IAPVerifier d__2` appends txn.Id to
 * the PlayerPrefs "IAPTRANSACTIONS" list ONLY when its own dc.state ends Accepted,
 * and rejects early when the list contains the id. A/B sit *downstream* of that
 * decision — d__2 still ends CantVerify → nothing is ever appended → state0's
 * `processed.Contains` early-out never fires → repeat purchases always verify.
 * (Option C, forcing d__2 itself, was rejected: with FreeStore's fixed
 * `orderId "GPA.morphe"` the 2nd purchase would be Rejected before verification.)
 *
 * ============================================================================
 * DELIVERY — how the patched .so lands in the XAPK/split pipeline (verified)
 * ============================================================================
 * Open question from the notes resolved by reading morphe-patcher-1.5.2
 * (javap on ApkMerger / PatchEngine / ArsclibResourceCoder / ResourcePatchContext)
 * and by CRC-checking the already-built output:
 *
 *  1. morphe-cli input is the `.apks`; PatchEngine runs `ApkMerger.merge()` FIRST
 *     (ApkBundle.loadApkDirectory + mergeModules) → splits (`config.arm64_v8a.apk`,
 *     `config.armeabi_v7a.apk`, …) become ONE merged APK before any patch executes.
 *     Both config splits carry `lib/<abi>/libil2cpp.so` (verified with unzip -l).
 *  2. A rawResourcePatch anywhere in the patch set forces ResourceMode.RAW_ONLY:
 *     decode = `ApkModuleRawDecoder` raw-extract of the merged APK into the working
 *     dir, libs at `root/lib/<abi>/libil2cpp.so`. `get(path, true)` resolves exactly
 *     there (`otherResourcesRootDirectory = workingDir/root`; the boolean is a
 *     vestigial `uncompress` hint — unused by the Arsclib coder, kept `true` to
 *     match the ITD2/CrossyRoad call sites).
 *  3. On encode, `detectFileChanges()` re-snapshots the extracted root tree (lastModified+length)
 *     → our in-place write (same length, new mtime) lands in
 *     `modifiedBinaryResources` → `getOtherResourceFiles(RAW_ONLY)` returns it →
 *     `ApkUtils.applyTo` overlays it into `rebuilt.apk` → `signWithLegacyFallback`.
 *  4. EVIDENCE the roundtrip is lossless + merge carries libs: the previous
 *     FreeStore build's `analysis/deadtrigger/deadtrigger_2.3.4_patched.apk`
 *     contains BOTH `lib/arm64-v8a/libil2cpp.so` (51,249,224 B, CRC32 42926e38) and
 *     `lib/armeabi-v7a/libil2cpp.so` (42,902,220 B, CRC32 9ec488d2) — byte-identical
 *     CRC to the original config splits, proving merge+raw-decode+encode preserves
 *     lib bytes exactly, so a 4-byte edit survives to the signed output.
 *
 * Static file patch (chosen) vs runtime companion .so (ITD2 recipe): DT's
 * `libil2cpp.so` `.text` is plaintext on disk (disassembled directly, both ABIs),
 * there is no .so integrity/signature/anti-tamper check anywhere in the chain
 * (signature-bypass.md + notes §5), and the whole APK is re-signed as one unit —
 * so a static 4-byte edit needs no NDK companion, no System.loadLibrary trigger,
 * and no mprotect dance. It also sidesteps the ubisoftpop packed-lib timing lesson
 * outright: nothing is touched at runtime, before or after Unity's native init.
 *
 * ============================================================================
 * STRUCTURE — inline rawResourcePatch dependency of Dead Trigger Free Store
 * ============================================================================
 * A bytecodePatch's context (BytecodePatchContext) has NO file API — `get(path)`
 * exists only on ResourcePatchContext — so the .so edit physically cannot live
 * inside `deadTriggerFreeStorePatch`'s execute{} (CrossyRoad KDoc documents this
 * same javap finding). Two sanctioned shapes exist:
 *  - CrossyRoad: ONE listing entry via inline `dependsOn(rawResourcePatch{…})`
 *    (native half hidden, atomic toggle) ← CHOSEN for this merge;
 *  - ITD2/ubisoftpop: separate discoverable patches per concern, all default=true
 *    (the shape this file used before consolidation — kept here only as rationale
 *    for why the two halves must travel together).
 * Chosen: **CrossyRoad shape**. Everything below is javap-verified against
 * morphe-patcher-1.5.2:
 *
 *  - DISCOVERY: PatchLoader scans `Class.getFields()` (public fields of Patch type)
 *    and `Class.getMethods()` (public STATIC zero-arg methods returning Patch), then
 *    keeps entries whose `name != null`. A top-level `val … = rawResourcePatch(…)`
 *    IS such a public static field — which is exactly why this file no longer
 *    declares one. [NativeIapVerifierBypass.patch] is a member of an `internal
 *    object`, i.e. an INSTANCE method; `PatchLoader.Companion.canAccess` requires
 *    `Modifier.isStatic`, so neither the builder nor the private helpers below are
 *    ever discovered. list-patches / patches-list.json expose exactly ONE Dead
 *    Trigger entry: "Dead Trigger Free Store".
 *  - GRAPH: the returned Patch exists only inside the parent's `dependencies`
 *    LinkedHashSet — reachable from the patch object graph, invisible to discovery.
 *  - EXECUTION: `Patcher.plusAssign` adds a selected patch's dependencies to
 *    `allPatches` recursively, and `Patcher.invoke` walks `getDependencies()` BEFORE
 *    executing each patch: the guarded .so edit runs first, then the DEX hooks.
 *    The halves touch disjoint artifacts (classes*.dex vs lib/<abi>/libil2cpp.so), so
 *    relative order is irrelevant. Because a RawResourcePatch sits anywhere in the
 *    graph, ResourceMode.RAW_ONLY is forced — `get("lib/<abi>/libil2cpp.so", true)`
 *    resolves inside this dependency's execute{}.
 *  - FLAGS: `default = true` here is documentation/parity only (CrossyRoad does the
 *    same). Selection flags (`getDefault()`/`getUse()` — `getUse()` literally
 *    returns the `default` field) are consulted solely for DISCOVERED top-level
 *    patches; the patcher never gates a dependency on them. This dependency runs
 *    if and only if its parent runs. Result reporting: dependency results are
 *    memoized but only top-level selections are emitted, so a successful run lists
 *    just "Dead Trigger Free Store" in `appliedPatches`; a failure in EITHER half
 *    surfaces as the parent's PatchResult (the wrapped error text still names this
 *    dependency and carries its stack trace), and the parent's DEX hooks are then
 *    skipped (dependency short-circuit).
 *  - NO FINALIZE HAZARD: neither half declares a `finalize{}` block (the builder's
 *    finalizeBlock stays null), and dex/resource encoding runs centrally after ALL
 *    executes — the pipeline configuration (RAW_ONLY + bytecode mode) is identical
 *    to the previous two-patch layout, where both halves were already active
 *    together in one patch set.
 *
 * Anchors are guarded: each site's ORIGINAL 4 bytes are read and compared before
 * the replacement is written, so a moved/changed build fails loudly with a
 * PatchException (offset + expected/found hex) instead of corrupting the lib.
 * Version-locked to 2.3.4 via COMPATIBILITY_DEAD_TRIGGER (both ABIs).
 */
internal object NativeIapVerifierBypass {

    /**
     * Builds the native half of Dead Trigger Free Store — called exactly once,
     * from `../billing/FreeStorePatch.kt`'s `dependsOn(…)` argument.
     *
     * MUST stay a member of this object (an instance method): PatchLoader only
     * discovers `public static` Patch-valued fields/methods, so an instance
     * builder is invisible to listing while remaining fully executable as a
     * dependency. Do NOT convert this to a top-level `val`/`fun` — that would
     * re-register the native half as a SECOND selectable patch in list-patches
     * (a top-level `internal fun` is public static in bytecode and would be
     * discovered too; only private/file-level or instance members are safe).
     */
    fun patch(): RawResourcePatch = rawResourcePatch(
        name = "Dead Trigger Native IAP Verifier Bypass",
        description = "Native half of Dead Trigger Free Store: forces the game's built-in receipt check to approve purchases on your own device, so the gold and cash you tap are actually credited. Runs automatically with Dead Trigger Free Store; not separately selectable.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_DEAD_TRIGGER)

        execute {
            val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
            applyAnchors(arm64, "arm64-v8a", ARM64_ANCHORS)
            val armv7 = get("lib/armeabi-v7a/libil2cpp.so", true)
            applyAnchors(armv7, "armeabi-v7a", ARMV7_ANCHORS)
        }
    }
}

/** One guarded 4-byte instruction replacement in a libil2cpp.so. */
private class Anchor(
    /** Human label used in logs and PatchException messages. */
    val label: String,
    /** RVA in the shipped .so (arm64: file offset = RVA − 0x4000; armv7: offset = RVA). */
    val rva: Long,
    /** Exact file offset to patch. */
    val fileOffset: Long,
    /** Hex of the ORIGINAL instruction — must be present or the patch aborts. */
    val originalHex: String,
    /** Hex of the replacement instruction. */
    val replacementHex: String,
)

// arm64 — file offset = RVA − 0x4000 (.text vaddr 0x11fc980, LOAD delta 0x4000).
private val ARM64_ANCHORS = listOf(
    Anchor(
        label = "Buy_Internal d__33 state3: EBuyState ternary -> Success (Option A)",
        rva = 0x14AB688,
        fileOffset = 0x14A7688,
        originalHex = "2105891A",   // cinc w1, w9, ne   (Success:1 / Failure:2)
        replacementHex = "21008052", // mov w1, #1        (force Success)
    ),
    Anchor(
        label = "ConfirmTransaction d__37 state1: CantVerify callback -> Accepted (Option B)",
        rva = 0x14ABAC4,
        fileOffset = 0x14A7AC4,
        originalHex = "E1031F2A",   // mov w1, wzr       (callback(CantVerify=0))
        replacementHex = "21008052", // mov w1, #1        (callback(Accepted=1))
    ),
    Anchor(
        label = "ConfirmTransaction d__37 state2: final callback -> Accepted (Option B)",
        rva = 0x14ABA9C,
        fileOffset = 0x14A7A9C,
        originalHex = "011140B9",   // ldr w1, [x8, #0x10]  (w1 = dc.state)
        replacementHex = "21008052", // mov w1, #1            (force Accepted)
    ),
)

// armv7 — A32 (not Thumb), first LOAD is R E at vaddr 0 → file offset = RVA.
private val ARMV7_ANCHORS = listOf(
    Anchor(
        label = "Buy_Internal d__33 state3: EBuyState ternary -> Success (Option A)",
        rva = 0xA52544,
        fileOffset = 0xA52544,
        originalHex = "02500013",   // movwne r5, #2   (Failure when != Accepted)
        replacementHex = "0150A0E3", // mov r5, #1      (force Success)
    ),
    Anchor(
        label = "ConfirmTransaction d__37: merged shared callback -> Accepted (Option B)",
        rva = 0xA52AD0,
        fileOffset = 0xA52AD0,
        originalHex = "084099E5",   // ldr r4, [r9, #8]  (r4 = dc.state, sb==r9)
        replacementHex = "0140A0E3", // mov r4, #1        (force Accepted)
    ),
)

/**
 * Applies every [Anchor] to [lib] with an original-bytes guard.
 *
 * Reads only the 4 anchor bytes (no 50 MB slurp), writes in place (length unchanged
 * so the patcher's root/ snapshot diff is driven purely by the mtime bump), and
 * throws [PatchException] with full context if any original word is missing —
 * i.e. the anchor moved (new game build) and blind patching would corrupt code.
 */
private fun applyAnchors(lib: File, abi: String, anchors: List<Anchor>) {
    println("Dead Trigger native IAP ($abi): patching ${lib.name} (${lib.length()} bytes)")
    RandomAccessFile(lib, "rw").use { raf ->
        val size = raf.length()
        for (anchor in anchors) {
            if (size < anchor.fileOffset + 4) {
                throw PatchException(
                    "Dead Trigger native IAP ($abi): ${anchor.label} — file offset " +
                        "0x${anchor.fileOffset.toString(16)} is past end of ${lib.name} " +
                        "(size=$size) — app layout changed?",
                )
            }
            raf.seek(anchor.fileOffset)
            val actual = ByteArray(4)
            raf.readFully(actual)
            val expected = hex(anchor.originalHex)
            if (!actual.contentEquals(expected)) {
                throw PatchException(
                    "Dead Trigger native IAP ($abi): ${anchor.label} — anchor mismatch at " +
                        "RVA 0x${anchor.rva.toString(16)} (file 0x${anchor.fileOffset.toString(16)}): " +
                        "expected ${toHex(expected)} vs found ${toHex(actual)}. " +
                        "libil2cpp.so layout changed — unsupported app version?",
                )
            }
            raf.seek(anchor.fileOffset)
            raf.write(hex(anchor.replacementHex))
            println(
                "Dead Trigger native IAP ($abi): " +
                    "0x${anchor.rva.toString(16)}: ${anchor.originalHex} -> ${anchor.replacementHex} " +
                    "(${anchor.label})",
            )
        }
    }
}

/** Parses a plain hex string (no separators) into bytes. */
private fun hex(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

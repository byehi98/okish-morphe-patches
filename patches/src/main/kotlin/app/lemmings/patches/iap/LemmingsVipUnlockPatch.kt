package app.lemmings.patches.iap

import app.lemmings.patches.shared.Constants.COMPATIBILITY_LEMMINGS
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile

/**
 * Lemmings: Puzzle Survival 9.5.0 · **VIP Unlock** — VIP reads as owned/active
 * client-side without purchase. Companion to `lemmingsNativeGrantHookPatch`
 * (`LemmingsNativeGrantHookPatch.kt` — store grant via the game's own reward
 * chain): that covers the store grant funnel; THIS patch covers
 * the subscription/VIP flag.
 *
 * Unity IL2CPP title: every VIP UI read in the game funnels through ONE
 * native method with `(tag=4, period=4)` — 25 caller files verified (all VIP
 * banners, popups, resurrect, store, reward wheels, energy bar, XP system,
 * pinball save, referral, MakerHub, instruction unlocks). Full evidence in
 * `analysis/com.sadpuppy.lemmings/notes/vip-unlock.md`.
 *
 * ============================================================================
 * PRIMARY — `Subscriptions::IsSubscriptionActive` → return true at entry
 * ============================================================================
 * * The CENTRAL VIP/subscription gate. Body: `BL HasActiveVipTrial` → true ⇒
 *   return 1 (early-true path); else `Data.IsSubscriptionActive` on the local
 *   save field (`this+0x38`) → true ⇒ return 1; else tail-calls the same check
 *   on the Nebula/server-override field (`this+0x40`).
 * * file offset **0x1C646B0** (Cpp2IL 9.5.0 `AddressAttribute` value == file
 *   offset directly — this build has NO `RVA − 0x4000` bias; 9.4.4 was
 *   `0x1BF6594`).
 * * Entry bytes (shipped lib, md5 `502bd49e80824697bf376a2f0e4c8bb1`,
 *   anchor re-verified by exact-byte search + Cpp2IL declaration — IDENTICAL
 *   to 9.4.4):
 *   `FE 57 BE A9  F4 4F 01 A9  F3 03 02 2A  F4 03 01 2A`
 *   (`stp x30,x21,[sp,#-32]! ; stp x20,x19,[sp,#16] ; mov w19,w2 ; mov w20,w1`).
 * * Patch: word0 `FE57BEA9` → `20008052` (`mov w0,#1`), word1 `F44F01A9` →
 *   `C0035FD6` (`ret`) — the method returns 1 immediately; the trial check,
 *   the local-save read and the Nebula-override tail-call never run.
 * * Guard: all 16 bytes must match exactly (no ADR/BL in the matched words —
 *   position-independent prologue, fully stable mask). Mismatch → BACKUP is
 *   still attempted; both mismatch → loud [PatchException], never blind write.
 *
 * ============================================================================
 * BACKUP — `Subscriptions::HasActiveVipTrial` → return true at entry
 * ============================================================================
 * * The trial shortcut at the top of PRIMARY (`TBNZ → TRUE`): forcing it true
 *   makes PRIMARY return true without touching PRIMARY's bytes, through the
 *   same choke point with an even smaller method. Bundled together with
 *   PRIMARY (disjoint offsets, 100 bytes apart — no overlap, no interaction):
 *   either one alone is sufficient per the notes; both applied is belt and
 *   suspenders against a partially-drifted rebuild.
 * * file offset **0x1C64714** (Cpp2IL 9.5.0; 9.4.4 was `0x1BF65F8` — the two
 *   sites keep their `0x64`/100-byte adjacency, disjoint, no interaction).
 * * Entry bytes (xxd-verified):
 *   `FE 7F BF A9  E1 23 00 91  ...`
 *   (`stp x30,xzr,[sp,#-16]! ; add x1,sp,#8` — the 3rd word is the
 *   `bl TryGetTrialData`, relocation-sensitive, excluded from the mask).
 * * Patch: same 8-byte `mov w0,#1 ; ret` shape (`20008052 C0035FD6`).
 * * Guard: first 8 bytes exact. Mismatch → logged, PRIMARY still stands.
 *
 * Success/grant paths provably untouched: `ShowRewardReceipt`, `ProcessPurchase`
 * and the `AddSubscription` purchase-completion writers are separate methods;
 * the writes are 8 + 8 bytes at two file offsets. Only
 * `lib/arm64-v8a/libil2cpp.so` is edited; the armv7 lib has NO
 * hunter-verified anchor (different ISA), so it is left byte-identical
 * (logged, not fatal — arm64 covers all modern devices).
 *
 * Delivery: top-level `rawResourcePatch` (pure-native DeadTarget shape — no
 * DEX half, so no `bytecodePatch` wrapper needed), ONE listing entry
 * "Lemmings: VIP Unlock", RAW_ONLY forces lib/ extraction so
 * `get("lib/<abi>/libil2cpp.so", true)` resolves.
 *
 * Device test: VIP banners/popups/badges read active; store VIP slots light
 * up; no purchase flow involved.
 */
@Suppress("unused")
val lemmingsVipUnlockPatch = rawResourcePatch(
    name = "Lemmings: VIP Unlock",
    description = "Unlocks VIP status without paying. VIP banners, rewards and bonuses show as active — no subscription needed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LEMMINGS)

    execute {
        val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
        if (!arm64.exists()) {
            throw PatchException(
                "Lemmings VIP unlock: lib/arm64-v8a/libil2cpp.so absent — " +
                    "unexpected split layout?",
            )
        }
        applyVipAnchors(arm64)

        // armv7: NO hunter-verified anchor (Thumb-2 needs its own
        // analysis) — leave byte-identical, say so loudly.
        val armv7 = get("lib/armeabi-v7a/libil2cpp.so", true)
        if (armv7.exists()) {
            println(
                "Lemmings VIP unlock: lib/armeabi-v7a/libil2cpp.so present " +
                    "but no verified armv7 anchor — leaving untouched " +
                    "(arm64 patched).",
            )
        }
    }
}

/** PRIMARY native unlock site (TARGET 1 — gate → TRUE at entry). */
private const val VIP_PRIMARY_OFFSET = 0x1C646B0L
private const val VIP_PRIMARY_ORIG = "FE57BEA9F44F01A9F303022AF403012A"

/** BACKUP native unlock site (TARGET 2 — trial shortcut → TRUE at entry). */
private const val VIP_BACKUP_OFFSET = 0x1C64714L
private const val VIP_BACKUP_ORIG = "FE7FBFA9E1230091"

/** `mov w0,#1 ; ret` — the compiler's own true-return idiom for these methods. */
private const val VIP_TRUE_STUB = "20008052C0035FD6"

/**
 * Applies the VIP true-stubs: PRIMARY first, then BACKUP (disjoint methods —
 * both are applied when both anchors match). Throws [PatchException] (no
 * write) if NEITHER anchor matches — a moved build must fail loudly, never
 * corrupt the lib.
 */
private fun applyVipAnchors(lib: File) {
    println("Lemmings VIP unlock: patching ${lib.name} (${lib.length()} bytes)")
    val primary = tryVipAnchor(lib, VIP_PRIMARY_OFFSET, VIP_PRIMARY_ORIG, "Subscriptions::IsSubscriptionActive (PRIMARY)")
    val backup = tryVipAnchor(lib, VIP_BACKUP_OFFSET, VIP_BACKUP_ORIG, "Subscriptions::HasActiveVipTrial (BACKUP)")
    if (!primary && !backup) {
        throw PatchException(
            "Lemmings VIP unlock: NEITHER anchor matched " +
                "(PRIMARY file 0x${VIP_PRIMARY_OFFSET.toString(16)}, " +
                "BACKUP file 0x${VIP_BACKUP_OFFSET.toString(16)}). " +
                "libil2cpp.so layout changed — unsupported app version?",
        )
    }
    if (!primary) {
        println(
            "Lemmings VIP unlock: PRIMARY anchor mismatch — BACKUP applied, " +
                "VIP still unlocks through the trial shortcut.",
        )
    }
    if (!backup) {
        println(
            "Lemmings VIP unlock: BACKUP anchor mismatch — PRIMARY applied, " +
                "VIP unlocks through the central gate.",
        )
    }
}

/** Reads the anchor, compares, writes the true-stub in place. True if applied. */
private fun tryVipAnchor(lib: File, offset: Long, originalHex: String, label: String): Boolean {
    RandomAccessFile(lib, "rw").use { raf ->
        if (raf.length() < offset + 8) return false
        raf.seek(offset)
        val anchorLen = originalHex.length / 2
        val actual = ByteArray(anchorLen)
        raf.readFully(actual)
        if (!actual.contentEquals(vipHexBytes(originalHex))) {
            println(
                "Lemmings VIP unlock: $label anchor mismatch at file " +
                    "0x${offset.toString(16)} (found ${vipToHex(actual)}).",
            )
            return false
        }
        raf.seek(offset)
        raf.write(vipHexBytes(VIP_TRUE_STUB))
        println(
            "Lemmings VIP unlock: $label file 0x${offset.toString(16)}: " +
                "${originalHex.substring(0, 8)} ${originalHex.substring(8, 16)} -> " +
                "20008052 C0035FD6 (mov w0,#1; ret)",
        )
        return true
    }
}

/** Parses a plain hex string (no separators) into bytes. */
private fun vipHexBytes(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun vipToHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

package app.lemmings.patches.iap

import app.lemmings.patches.shared.Constants.COMPATIBILITY_LEMMINGS
import app.lemmings.patches.shared.NativeBytePatcher
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Lemmings: Puzzle Survival 9.5.1 · **VIP Unlock** — VIP reads as owned/active
 * client-side without purchase. Companion to `lemmingsNativeGrantHookPatch`
 * (`LemmingsNativeGrantHookPatch.kt` — store grant via the game's own reward
 * chain): that covers the store grant funnel; THIS patch covers the
 * subscription/VIP flag.
 *
 * Unity IL2CPP title: every VIP UI read in the game funnels through ONE
 * native method with `(tag=4, period=4)` — 25 caller files verified (all VIP
 * banners, popups, resurrect, store, reward wheels, energy bar, XP system,
 * pinball save, referral, MakerHub, instruction unlocks).
 *
 * **Scope for 9.5.1: arm64-v8a only.** The 9.5.1 APKPure XAPK ships base +
 * `config.arm64_v8a` only (versionCode 7952). APKPure publishes a separate
 * armeabi-v7a variant, but it was not obtained, so no 9.5.1 armv7 offsets
 * could be derived — the 9.5.0 armv7 addresses are stale and are deliberately
 * NOT applied (the loop warns instead of writing to a drifted lib).
 *
 * Rebase method: capstone-disassembled both the 9.5.0 arm64 lib
 * (md5 502bd49e80824697bf376a2f0e4c8bb1) and the 9.5.1 arm64 lib
 * (md5 a67418b38a6d07e59c0c4a7dcd98a118) and matched the two gates on their
 * prologue + body shape. Both sites moved by the same **+0x25778** shift and
 * kept their 0x64 (100-byte) adjacency, so only the absolute offsets changed.
 *
 * ============================================================================
 * PRIMARY — `Subscriptions::IsSubscriptionActive` → return true at entry
 * ============================================================================
 * * The CENTRAL VIP/subscription gate. Body: `BL HasActiveVipTrial` → true ⇒
 *   return 1 (early-true path); else `Data.IsSubscriptionActive` on the local
 *   save field (`this+0x38` arm64 / `this+0x20` armv7) → true ⇒ return 1;
 *   else tail-calls the same check on the Nebula/server-override field
 *   (`this+0x40` arm64 / `this+0x24` armv7).
 * * arm64 file offset **0x1C89E28** for 9.5.1 (9.5.0 was **0x1C646B0**,
 *   delta +0x25778; `VA = file + 0x4000`, lib md5
 *   `a67418b38a6d07e59c0c4a7dcd98a118`). Entry bytes (9.5.1 lib, capstone +
 *   xxd-verified):
 *   `FE 57 BE A9  F4 4F 01 A9  F3 03 02 2A  F4 03 01 2A`
 *   (`stp x30,x21,[sp,#-32]! ; stp x20,x19,[sp,#16] ; mov w19,w2 ; mov w20,w1`)
 *   — byte-identical prologue to 9.5.0 at the shifted address, and the body
 *   still tail-calls the trial check at 0x1C89E8C.
 * * Patch: word0/word1 → return-true stub — the trial check, the local-save
 *   read and the Nebula-override tail-call never run.
 *   * arm64 `mov w0,#1 ; ret` = `20008052 C0035FD6`
 * * Guard: arm64 all 16 bytes must match exactly. Mismatch → BACKUP is still
 *   attempted; both mismatch on an ABI → loud [PatchException], never a blind
 *   write.
 *
 * ============================================================================
 * BACKUP — `Subscriptions::HasActiveVipTrial` → return true at entry
 * ============================================================================
 * * The trial shortcut at the top of PRIMARY (`TBNZ → TRUE`): forcing it true
 *   makes PRIMARY return true without touching PRIMARY's bytes, through the
 *   same choke point with an even smaller method. Bundled together with
 *   PRIMARY (disjoint offsets): either one alone is sufficient; both applied is
 *   belt and suspenders against a partially-drifted rebuild.
 * * arm64 file offset **0x1C89E8C** for 9.5.1 (9.5.0 was **0x1C64714** — the
 *   two sites keep their `0x64`/100-byte adjacency). Entry bytes:
 *   `FE 7F BF A9  E1 23 00 91  …`
 *   (`stp x30,xzr,[sp,#-16]! ; add x1,sp,#8` — the 3rd word is the
 *   `bl TryGetTrialData`, relocation-sensitive, excluded from the mask).
 * * Patch: same return-true stub as PRIMARY (8 bytes).
 * * Guard: first 8 bytes exact. Mismatch → logged, PRIMARY still stands.
 *
 * Success/grant paths provably untouched: `ShowRewardReceipt`, `ProcessPurchase`
 * and the `AddSubscription` purchase-completion writers are separate methods;
 * the writes are 8 + 8 bytes at two file offsets per ABI.
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
        var patchedAbis = 0
        for (abi in LemmingsVipAbi.all) {
            val lib = get("lib/${abi.id}/libil2cpp.so", true)
            if (!lib.exists()) {
                println("Lemmings VIP unlock: lib/${abi.id}/libil2cpp.so absent — skipping ABI.")
                continue
            }
            applyVipAnchors(lib, abi)
            patchedAbis++
        }
        for (abiId in LemmingsVipAbi.unsupported) {
            val lib = runCatching { get("lib/$abiId/libil2cpp.so", true) }.getOrNull() ?: continue
            if (lib.exists()) {
                println(
                    "Lemmings VIP unlock: WARNING lib/$abiId/libil2cpp.so is present but 9.5.1 " +
                        "$abiId offsets are not derived — this ABI is left UNPATCHED.",
                )
            }
        }
        if (patchedAbis == 0) {
            throw PatchException(
                "Lemmings VIP unlock: no libil2cpp.so found in any supported ABI split " +
                    "(arm64-v8a) — unexpected split layout?",
            )
        }
    }
}

/** Per-ABI VIP anchor set: both sites + the ABI's own return-true stub. */
private class LemmingsVipAbi(
    val id: String,
    val versionNote: String,
    val primaryOffset: Long,
    val primaryOrig: String,
    val primaryLabel: String,
    val backupOffset: Long,
    val backupOrig: String,
    val backupLabel: String,
    val trueStub: String,
) {
    companion object {
        /**
         * 9.5.1 arm64 (md5 a67418b38a6d07e59c0c4a7dcd98a118, VA = file + 0x4000).
         * Entry prologues are position-independent (no ADR/BL in the guard).
         */
        val ARM64 = LemmingsVipAbi(
            id = "arm64-v8a",
            versionNote = "9.5.1 arm64 (+0x25778 vs 9.5.0)",
            primaryOffset = 0x1C89E28L,
            primaryOrig = "FE57BEA9F44F01A9F303022AF403012A",
            primaryLabel = "Subscriptions::IsSubscriptionActive (PRIMARY)",
            backupOffset = 0x1C89E8CL,
            backupOrig = "FE7FBFA9E1230091",
            backupLabel = "Subscriptions::HasActiveVipTrial (BACKUP)",
            trueStub = "20008052C0035FD6", // mov w0,#1 ; ret
        )

        /**
         * 9.5.0 armeabi-v7a (md5 e1fe5b5b8a45c02704dbb5aca6a22116, VA == file
         * offset). ARM state (A32), not Thumb. Guard words are all
         * position-independent (pushes/movs only).
         *
         * NOT applied for 9.5.1: no 9.5.1 armv7 lib was obtained, so these
         * offsets are stale. Kept for reference only — see [all].
         */
        val ARM32_9_5_0 = LemmingsVipAbi(
            id = "armeabi-v7a",
            versionNote = "9.5.0 armeabi-v7a (ARM state) — stale, not applied on 9.5.1",
            primaryOffset = 0xE32260L,
            primaryOrig = "F0482DE90240A0E10150A0E10060A0E1",
            primaryLabel = "Subscriptions::IsSubscriptionActive (PRIMARY)",
            backupOffset = 0xE322D0L,
            backupOrig = "10402DE908D04DE2",
            backupLabel = "Subscriptions::HasActiveVipTrial (BACKUP)",
            trueStub = "0100A0E31EFF2FE1", // mov r0,#1 ; bx lr
        )

        /** 9.5.1 APKPure XAPK ships `config.arm64_v8a` only. */
        val all = listOf(ARM64)

        /** ABIs whose 9.5.1 offsets are not derived yet — skipped with a warning. */
        val unsupported = listOf("armeabi-v7a")
    }
}

/**
 * Applies the VIP true-stubs for one ABI: PRIMARY first, then BACKUP (disjoint
 * methods — both are applied when both anchors match). Throws
 * [PatchException] (no write) if NEITHER anchor matches — a moved build must
 * fail loudly, never corrupt the lib.
 */
private fun applyVipAnchors(lib: java.io.File, abi: LemmingsVipAbi) {
    val tag = "Lemmings VIP unlock [${abi.id}]"
    println("$tag: patching ${lib.name} (${lib.length()} bytes; ${abi.versionNote})")

    val primary = NativeBytePatcher.apply(
        lib, abi.primaryOffset, abi.primaryOrig, abi.trueStub, abi.primaryLabel, tag,
    )
    val backup = NativeBytePatcher.apply(
        lib, abi.backupOffset, abi.backupOrig, abi.trueStub, abi.backupLabel, tag,
    )
    if (!primary && !backup) {
        throw PatchException(
            "$tag: NEITHER anchor matched " +
                "(PRIMARY file 0x${abi.primaryOffset.toString(16)}, " +
                "BACKUP file 0x${abi.backupOffset.toString(16)}). " +
                "libil2cpp.so layout changed — unsupported app version?",
        )
    }
    if (!primary) {
        println(
            "$tag: PRIMARY anchor mismatch — BACKUP applied, " +
                "VIP still unlocks through the trial shortcut.",
        )
    }
    if (!backup) {
        println(
            "$tag: BACKUP anchor mismatch — PRIMARY applied, " +
                "VIP unlocks through the central gate.",
        )
    }
}

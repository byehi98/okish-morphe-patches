package app.swampattack2.patches.iap

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.swampattack2.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK_2
import java.io.File
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Swamp Attack 2 v1.3.10 · **Unlimited Currency (static native patch)**.
 *
 * Unity IL2CPP title: game logic lives 100 % in `libil2cpp.so`, there is no
 * game DEX code (see notes/premium-bypass.md §3, targets C1–C8). This patch
 * edits the game's OWN `libil2cpp.so` bytes at patch time — both
 * `lib/arm64-v8a/` and `lib/armeabi-v7a/` — and writes them back in place.
 *
 * There is NO bytecodePatch here, NO companion `.so`, NO `System.loadLibrary`
 * trigger, and `UnityPlayerActivity.onCreate` is left 100 % stock. Nothing of
 * ours executes at launch, so launch can no longer crash from our code (the
 * old `UnsatisfiedLinkError` vector — injected `loadLibrary("SwampUnlimited")`
 * into `onCreate` — is gone with the deleted trigger patch).
 *
 * ============================================================================
 * WHAT IS FORCED — the safest always-true spend gates only (6 sites per ABI)
 * ============================================================================
 * Every site below returns `bool` and is overwritten at its first 8 bytes
 * with a return-true stub, so the original body (including the deduction)
 * never runs:
 *
 *   arm64  `mov w0, #1 ; ret`  = 20 00 80 52  C0 03 5F D6
 *   arm32  `mov r0, #1 ; bx lr` (ARM state) = 01 00 A0 E3  1E FF 2F E1
 *
 * Overwriting the prologue is safe: the stub returns immediately and never
 * touches the stack, so the discarded frame setup is simply never executed —
 * no `sp` imbalance, no link-register leak (`ret` / `bx lr` returns to the
 * untouched caller). The remaining bytes of each 16-byte verify window past
 * the 8-byte stub become unreachable dead code.
 *
 * Effect: `CanAfford*` always passes (shop buttons stay enabled) and every
 * `TryTake*` spend reports success WITHOUT deducting — coins, gems, cards,
 * boosters, premium ammo, every `GenericResourceType` is effectively free,
 * while real balances only grow.
 *
 * Additionally `PlayerProfile.GetResourceAmount` (C1) is pinned to return
 * 99,999,999 for EVERY resource type (see DISPLAY PIN below), so the wallet
 * HUD (`CurrencyPanelComponent`), shop prices and all other balance readouts
 * show an unlimited number instead of the real (low) balance.
 *
 * ============================================================================
 * DISPLAY PIN — GetResourceAmount -> 99999999 (1 site per ABI)
 * ============================================================================
 * Target: `PlayerData.PlayerProfile.GetResourceAmount(GenericResource)`
 * (dump.cs TypeDef 837 in 1.3.10; script.json `PlayerData.PlayerProfile$$GetResourceAmount`).
 * The method is a 16-byte fast-path forwarder: load `inventory` (`[x0,#0x128]`
 * arm64 / `[r0,#0xC0]` arm32), null-check, zero the 3rd arg, tail-branch to
 * `ResourcePack.GetAmount` (arm64 RVA `0x1D2383C` in 1.3.10 —
 * confirmed via script.json, NOT via GetResourceAmount itself). Overwriting
 * the prologue with a constant-return stub is therefore safe by the same
 * argument as the spend gates (immediate return, no stack touched; the
 * null-inventory cold path becomes unreachable dead code — and the stub never
 * dereferences anything, so a null profile/inventory can no longer crash
 * here, it just reads 99,999,999).
 *
 * Stub (16 bytes, returns 99999999 = 0x5F5E0FF in the int return register):
 *
 *   arm64  `movz w0, #0xE0FF ; movk w0, #0x5F5, lsl #16 ; ret ; nop`
 *       =  E0 1F 9C D2  A0 BE A0 F2  C0 03 5F D6  1F 20 03 D5
 *   arm32  `movw r0, #0xE0FF ; movt r0, #0x5F5 ; bx lr ; nop` (ARM state)
 *       =  FF 00 0E E3  F5 05 40 E3  1E FF 2F E1  00 00 A0 E1
 *
 * Gate widths are deliberately WIDE (32 bytes / 8 words, all verified
 * byte-exact against the issue XAPK's own splits):
 * * arm64 needs only 16 (word 4 `1400F49B` already differs from every
 *   `CanAfford` prologue), but 32 pins the forwarder + the null-path head.
 * * arm32 NEEDS >16: the first 16 bytes of `GetResourceAmount` are
 *   byte-identical to `CanAfford(ResourcePack)` and
 *   `CanAfford(InventoryItem)` (`E92D4830 E59050C0 E1A04001 E3550000` —
 *   same compiler prologue shape). Word 6 disambiguates (`EBF983D3` vs
 *   `EBF983F6`/`EBF983EB`), so the 32-byte gate can never mis-hit a spend
 *   gate even though the pinned offsets already differ.
 *
 * ----------------------------------------------------------------------------
 * TRADEOFF VERDICT — return-constant (all types) vs type-conditional (Currency)
 * ----------------------------------------------------------------------------
 * The preferred `max(real, 99999999)`-only-for-`Currency` was considered and
 * REJECTED for a static patch; return-constant-99999999 is implemented:
 *
 * 1. A conditional needs ~24–36 bytes (`ldr w8,[x1,#typeOff]` + `cmp #1` +
 *    `b.eq` + 12-byte constant stub + reconstructed original prologue for the
 *    fall-through). `GetResourceAmount` owns only 16 bytes before its
 *    null-inventory cold path; the check would have to destroy that cold
 *    path (startup crash risk — the exact thing this patch must never
 *    cause) or the tail-branch setup. No clean fit.
 * 2. The type offset DIFFERS per ABI (`GenericResource.ResourceType` is at
 *    `0x20` arm64 but `0x10` arm32 — dump.cs TypeDef 1939 in each dump), so
 *    one shared stub is impossible; two hand-encoded conditional stubs would
 *    double the mis-assembly risk with no runtime to test-branch.
 * 3. Blast-radius analysis (41 BL xrefs per ABI, resolved via script.json):
 *    every in-game caller is either DISPLAY (wallet HUD
 *    `CurrencyPanelComponent.Setup`, `ResourceChangeListener` x3, `ShopItem`
 *    / `ShopScreen` price lines, spin/character/reward popups) or
 *    BENIGN-LOGIC that an inflated value only helps (redneck upgrade counts
 *    with `TryTake` already forced true, `CardsUntilUpgrade`, spin
 *    eligibility, missing-resource popups). The spend path itself does NOT
 *    consult this method (`CanAfford` forwards to `ResourcePack.CanAfford`,
 *    `HasResource` calls `ResourcePack`-level helpers directly — both
 *    verified by disassembly), and the stored `ResourcePack` inventory is
 *    never modified, so saves + cloud uploads keep REAL amounts; only live
 *    reads inflate. Worst observable effects: non-currency counts (cards,
 *    boosters, ammo, ooze, skins) DISPLAY 99,999,999 in upgrade/craft popups,
 *    and dev-analytics (`AnalyticsHelper.SendResourcesStatus` etc.) logs big
 *    balances. No save corruption, no logic inversion, no crash vector.
 *    This matches the old runtime engine's visible behaviour (wallet reads
 *    unlimited) while game progression stays spend-free via the gates.
 * Callers that could observe inflated non-currency counts: `GameData.
 * FilterRednecks`, `OffersManager.CardsUntilUpgrade`, `MainMenuScreen.
 * CheckForNewRednecks`, `RedneckInfoPopup.SetupRedneck/OnUpgrade`,
 * `CharacterSlot.UpdateData`, `RewardCard.Setup`, `MissingCardsPopup.Setup`,
 * `PremiumWeaponPopup.Setup`, `WinReward.Setup`, `BattlePassSeason.
 * InitializeSeason`, spin-wheel UI, `RestoreManager.ReportRestoreOffered`
 * (x4), `CloudOnceManager.SetCloudProfile` (x2, diff/log reads only),
 * `AnalyticsHelper.*` (x4). None gate correctness on the exact value.
 *
 * ============================================================================
 * SITES — RVAs from dump.cs + script.json, gate words from the shipping libs
 * ============================================================================
 * arm64 file offset = RVA − 0x4000 (R-E LOAD off 0x1910680 = vaddr 0x1914680,
 * verified from the ELF program headers of the 1.3.10 XAPK's own
 * config.arm64_v8a.apk split — see notes/gates-1.3.10.md). arm32 file offset = RVA (single R-E LOAD at
 * off 0 / vaddr 0). All 12 first-16-byte gate words were verified byte-exact
 * against the issue XAPK's own splits (which are sha256-identical to
 * analysis/.../native/libil2cpp.so and .../il2cpp32/libil2cpp.so).
 *
 * Each site is gated on its exact 16 expected bytes at the pinned offset: a
 * mismatch SKIPS that site (logged, never written blind). A new game build
 * therefore degrades to fewer (or zero) gates instead of corrupting the lib.
 * If zero sites apply across both ABIs the patch throws [PatchException]
 * rather than shipping a silent no-op.
 *
 * ============================================================================
 * DELIBERATELY LEFT OUT (v1 — minimal by design)
 * ============================================================================
 * * `GetResourceAmount` balance pin — not needed: balances never decrease,
 *   so the real display value is already "enough". Skips display-layer risk.
 *   (v2: ADDED — see DISPLAY PIN above. The v1 caution is superseded: the
 *   forwarder shape + xref analysis showed a constant stub is as safe as the
 *   spend gates, and the user explicitly wants the visible number unlimited.)
 * * `HasResource` x2, `ResourcePack` pack-side gates x5 — redundant for the
 *   spend loop (profile-level `TryTake` covers the documented funnel).
 * * `HandleAddingResource` grant multiplier — needs a trampoline/relocation
 *   mechanism; out of scope for a static v1 with no runtime code.
 * * `AdsData` native ads gates — out; ads are covered by the separate
 *   ABI-independent Java `../ads/AdRemovalPatch` (rewarded offers behave
 *   stock: real ad, real reward).
 * * `SetResourceAmount`, IAP grant (`OnSuccessfulPurchaseForRealCurrency`),
 *   server-validation bypass, adjustment path — untouched, see
 *   notes/premium-bypass.md I1–I3 (server-validated receipts can't be forged
 *   statically; not attempted).
 * * No new `.so` files, no manifest/DEX edits, no trampolines/relocation.
 */
@Suppress("unused")
val unlimitedCurrencyPatch = rawResourcePatch(
    name = "Swamp Attack 2: Unlimited Currency",
    description = "Never run out of coins, gems or other resources. Buying things never lowers your balance. Note: ignore the coins/diamonds value shown — even if it shows less than the item price, just tap the item and it will be granted.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK_2)

    execute {
        var gates = 0
        var pins = 0

        val arm64 = get("lib/arm64-v8a/libil2cpp.so", true)
        if (arm64.exists()) {
            gates += patchAbi(arm64, "arm64-v8a", ARM64_SITES, expectedElfClass = 2)
            pins += patchAbi(arm64, "arm64-v8a", ARM64_DISPLAY, expectedElfClass = 2)
        } else {
            println("Swamp Attack 2 unlimited currency: lib/arm64-v8a/libil2cpp.so absent — skipping ABI")
        }

        val arm32 = get("lib/armeabi-v7a/libil2cpp.so", true)
        if (arm32.exists()) {
            gates += patchAbi(arm32, "armeabi-v7a", ARM32_SITES, expectedElfClass = 1)
            pins += patchAbi(arm32, "armeabi-v7a", ARM32_DISPLAY, expectedElfClass = 1)
        } else {
            println("Swamp Attack 2 unlimited currency: lib/armeabi-v7a/libil2cpp.so absent — skipping ABI")
        }

        if (gates == 0 && pins == 0) {
            throw PatchException(
                "Swamp Attack 2 unlimited currency: no spend gates applied on any ABI — " +
                    "libil2cpp.so layout changed? Unsupported app version.",
            )
        }
        if (gates == 0) {
            println(
                "Swamp Attack 2 unlimited currency: WARNING — spend gates applied 0, " +
                    "only the display pin landed; purchases may still deduct.",
            )
        }
        if (pins == 0) {
            println(
                "Swamp Attack 2 unlimited currency: WARNING — display pin applied 0, " +
                    "wallet will read the real balance (spend gates still active).",
            )
        }
        println("Swamp Attack 2 unlimited currency: $gates spend gates + $pins display pins applied (static, no launch code)")
    }
}

/** One guarded byte-range replacement in `libil2cpp.so`. */
private class SpendGate(
    /** Human label used in logs. */
    val label: String,
    /** RVA from Il2CppDumper's script.json (provenance; kept for messages). */
    val rva: Long,
    /** Exact file offset written (RVA − LOAD delta, see KDoc). */
    val fileOffset: Long,
    /** The expected code words at [fileOffset] (table order = file order). */
    val expectWords: Array<String>,
    /** Optional replacement stub (file-order hex); defaults to the ABI return-true stub. */
    val stubHex: String? = null,
)

/** arm64 return-true stub: `mov w0, #1 ; ret` (8 bytes, file order). */
private const val STUB_ARM64 = "20008052C0035FD6"

/** arm32 return-true stub: `mov r0, #1 ; bx lr` (8 bytes, file order, ARM state). */
private const val STUB_ARM32 = "0100A0E31EFF2FE1"

/**
 * arm64 display stub (16 bytes, file order): `movz w0, #0xE0FF ; movk w0,
 * #0x5F5, lsl #16 ; ret ; nop` → w0 = 0x5F5E0FF = 99999999.
 */
private const val PIN_ARM64 = "E01F9CD2A0BEA0F2C0035FD61F2003D5"

/**
 * arm32 display stub (16 bytes, file order, ARM state): `movw r0, #0xE0FF ;
 * movt r0, #0x5F5 ; bx lr ; nop` → r0 = 0x5F5E0FF = 99999999.
 * NOTE: ARM movw/movt split imm16 as imm4:imm12 = bits[15:12]:bits[11:0];
 * 0x5F5 < 0x1000 so imm4 = 0, imm12 = 0x5F5 → word 0xE34005F5 (NOT 0xE34500F5,
 * which would assemble 0x50F5 — verified via capstone disassembly).
 */
private const val PIN_ARM32 = "FF000EE3F50540E31EFF2FE10000A0E1"

private val ARM64_SITES = listOf(
    SpendGate(
        label = "profile.CanAfford(ResourcePack) -> true",
        rva = 0x1CE657C,
        fileOffset = 0x1CE257C,
        expectWords = arrayOf("F9409400", "B4000060", "AA1F03E2", "1400F41A"),
    ),
    SpendGate(
        label = "profile.CanAfford(InventoryItem) -> true",
        rva = 0x1CE6594,
        fileOffset = 0x1CE2594,
        expectWords = arrayOf("F9409400", "B4000060", "AA1F03E2", "1400F78B"),
    ),
    SpendGate(
        label = "profile.CanAfford(GenericResource,int) -> true",
        rva = 0x1CE65AC,
        fileOffset = 0x1CE25AC,
        expectWords = arrayOf("F9409400", "B4000060", "AA1F03E3", "1400F739"),
    ),
    SpendGate(
        label = "profile.TryTakeResource(string,GenericResource,int,bool) -> true",
        rva = 0x1CE6CB4,
        fileOffset = 0x1CE2CB4,
        expectWords = arrayOf("F81C0FFE", "A9015FF8", "A90257F6", "A9034FF4"),
    ),
    SpendGate(
        label = "profile.TryTakeResource(string,InventoryItem,bool) -> true",
        rva = 0x1CE6FA0,
        fileOffset = 0x1CE2FA0,
        expectWords = arrayOf("B40000E2", "F9400C48", "B9401049", "12000064"),
    ),
    SpendGate(
        label = "profile.TryTakeResources(string,ResourcePack) -> true",
        rva = 0x1CE6FC4,
        fileOffset = 0x1CE2FC4,
        expectWords = arrayOf("D10283FF", "F90023FE", "A9056FFC", "A90667FA"),
    ),
)

private val ARM32_SITES = listOf(
    SpendGate(
        label = "profile.CanAfford(ResourcePack) -> true",
        rva = 0xEB4FD0,
        fileOffset = 0xEB4FD0,
        expectWords = arrayOf("E92D4830", "E59050C0", "E1A04001", "E3550000"),
    ),
    SpendGate(
        label = "profile.CanAfford(InventoryItem) -> true",
        rva = 0xEB4FFC,
        fileOffset = 0xEB4FFC,
        expectWords = arrayOf("E92D4830", "E59050C0", "E1A04001", "E3550000"),
    ),
    SpendGate(
        label = "profile.CanAfford(GenericResource,int) -> true",
        rva = 0xEB5028,
        fileOffset = 0xEB5028,
        expectWords = arrayOf("E92D4070", "E59060C0", "E1A04002", "E1A05001"),
    ),
    SpendGate(
        label = "profile.TryTakeResource(string,GenericResource,int,bool) -> true",
        rva = 0xEB58B8,
        fileOffset = 0xEB58B8,
        expectWords = arrayOf("E92D4BF0", "E24DD008", "E59F5178", "E1A07000"),
    ),
    SpendGate(
        label = "profile.TryTakeResource(string,InventoryItem,bool) -> true",
        rva = 0xEB5C40,
        fileOffset = 0xEB5C40,
        expectWords = arrayOf("E92D4BF0", "E24DD008", "E1A05003", "E1A07002"),
    ),
    SpendGate(
        label = "profile.TryTakeResources(string,ResourcePack) -> true",
        rva = 0xEB5C88,
        fileOffset = 0xEB5C88,
        expectWords = arrayOf("E92D4FF0", "E24DD02C", "E59F4254", "E1A0A000"),
    ),
)

/**
 * Display pins: `PlayerProfile.GetResourceAmount` → 99999999 (see DISPLAY PIN
 * in the file KDoc for the forwarder analysis + tradeoff verdict). 32-byte
 * gates, byte-verified against the issue XAPK's own splits (sha256-identical
 * to `analysis/.../native/libil2cpp.so` and `.../il2cpp32/libil2cpp.so`).
 * The arm32 gate MUST be 32 bytes: its first 16 bytes are identical to both
 * `CanAfford` prologues above (word 6 `EBF983D3` disambiguates).
 */
private val ARM64_DISPLAY = listOf(
    SpendGate(
        label = "profile.GetResourceAmount(*) -> 99999999 (wallet/display)",
        rva = 0x1CE65C4,
        fileOffset = 0x1CE25C4,
        expectWords = arrayOf(
            "F9409400", "B4000060", "AA1F03E2", "1400F49B",
            "F81F0FFE", "97F99AB4", "D101C3FF", "F9001BFE",
        ),
        stubHex = PIN_ARM64,
    ),
)

private val ARM32_DISPLAY = listOf(
    SpendGate(
        label = "profile.GetResourceAmount(*) -> 99999999 (wallet/display)",
        rva = 0xEB505C,
        fileOffset = 0xEB505C,
        expectWords = arrayOf(
            "E92D4830", "E59050C0", "E1A04001", "E3550000",
            "1A000000", "EBF983D3", "E1A00005", "E1A01004",
        ),
        stubHex = PIN_ARM32,
    ),
)

/**
 * Applies [sites] to [lib], gated per site on exact expected bytes.
 *
 * In-place writes (8-byte return-true stubs in 16-byte windows, 16-byte
 * constant stubs in 32-byte windows), so the patcher's `lastModified`-keyed
 * change diff picks them up. A site whose bytes don't match is SKIPPED with
 * a log line — never written blind. Returns the number of sites applied.
 */
private fun patchAbi(lib: File, abi: String, sites: List<SpendGate>, expectedElfClass: Int): Int {
    val bytes = lib.readBytes()
    println("Swamp Attack 2 unlimited currency: patching $abi libil2cpp.so (${bytes.size} bytes)")

    if (bytes.size < 64 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
    ) {
        throw PatchException(
            "Swamp Attack 2 unlimited currency: lib/$abi/libil2cpp.so is not an ELF file — " +
                "unexpected split layout?",
        )
    }
    // EI_CLASS (offset 4): 1 = 32-bit (ARM), 2 = 64-bit (AArch64).
    val eiClass = bytes[4].toInt() and 0xFF
    if (eiClass != expectedElfClass) {
        throw PatchException(
            "Swamp Attack 2 unlimited currency: lib/$abi/libil2cpp.so has ELF class $eiClass, " +
                "expected $expectedElfClass — ABI/lib mismatch, refusing to write.",
        )
    }

    val stub = hex(if (expectedElfClass == 2) STUB_ARM64 else STUB_ARM32)
    var applied = 0
    for (site in sites) {
        val expect = words(*site.expectWords)
        // A display-pin site carries a 16-byte constant stub but a 32-byte
        // gate; a spend-gate site uses the 8-byte return-true stub. Never
        // write more than verified: the replacement must fit in the window.
        val replacement = site.stubHex?.let(::hex) ?: stub
        if (replacement.size > expect.size) {
            println(
                "Swamp Attack 2 unlimited currency: SKIP ${site.label} [$abi] — " +
                    "stub (${replacement.size}B) wider than verified window " +
                    "(${expect.size}B), refusing to write blind.",
            )
            continue
        }
        val off = site.fileOffset
        if (off < 0 || off + expect.size > bytes.size) {
            println(
                "Swamp Attack 2 unlimited currency: SKIP ${site.label} [$abi] — " +
                    "file offset 0x${off.toString(16)} out of range (size=${bytes.size}).",
            )
            continue
        }
        val at = off.toInt()
        val actual = bytes.copyOfRange(at, at + expect.size)
        if (!actual.contentEquals(expect)) {
            println(
                "Swamp Attack 2 unlimited currency: SKIP ${site.label} [$abi] — " +
                    "expected ${toHex(expect)} at 0x${off.toString(16)} but found " +
                    "${toHex(actual.copyOf(8))}… (RVA 0x${site.rva.toString(16)}).",
            )
            continue
        }
        replacement.copyInto(bytes, at)
        applied++
        println(
            "Swamp Attack 2 unlimited currency: [$abi] ${site.label} " +
                "at file 0x${off.toString(16)} (RVA 0x${site.rva.toString(16)}) -> patched.",
        )
    }
    if (applied > 0) {
        lib.writeBytes(bytes)
    }
    println("Swamp Attack 2 unlimited currency: $abi — $applied/${sites.size} gates applied")
    return applied
}

/** Packs code words (as printed, e.g. "F9409400") into little-endian file bytes. */
private fun words(vararg w: String): ByteArray {
    val out = ByteArray(w.size * 4)
    w.forEachIndexed { i, s ->
        val v = s.toLong(16).toInt()
        out[i * 4] = (v and 0xFF).toByte()
        out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
        out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
        out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
    }
    return out
}

/** Parses a plain hex string (no separators, file byte order) into bytes. */
private fun hex(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX …" formatter for mismatch messages. */
private fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

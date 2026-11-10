package app.deadtarget.patches.unlock

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.186.0 — All items owned (armeabi-v7a).
 *
 * Every gun, gun skin, glove and drone shows as unlocked and can be equipped. The save file
 * is not changed. This is a pure static edit of `lib/armeabi-v7a/libil2cpp.so`.
 *
 * ============================================================================
 * ARCHITECTURE CHANGE (4.185.0 → 4.186.0)
 * ============================================================================
 * 4.185.0 shipped an **arm64-v8a** XAPK; 4.186.0 (versionCode 586009263) ships the
 * **armeabi-v7a** split only (`base` + `UnityDataAssetPack.apk` + `config.armeabi_v7a.apk`),
 * so libil2cpp.so is 76,435,716 bytes of **A32/ARM** code. None of the old AArch64 anchors
 * could ever match, and the old code would have died on
 * `get("lib/arm64-v8a/libil2cpp.so")` anyway. All fourteen sites were re-derived from an
 * Il2CppDumper run against the new library.
 *
 * **RVA == FILE OFFSET** in this ELF (`RVA:`/`Offset:`/`VA:` identical for every method), so
 * no +0x4000 correction is applied — that was an arm64-segment artefact.
 *
 * ============================================================================
 * THE FOURTEEN SITES AND WHAT EACH ONE FIXES
 * ============================================================================
 * Ownership in the 32-bit build is still "id present in a collection":
 *   * `Dictionary<int,int>.ContainsKey` — one shared generic implementation at 0x02D454E0,
 *     used by the guns (UserData.inventory), drones (UserData.droneInventory) and the
 *     raid-boss item list.
 *   * `List<int>.Contains` — two shared instantiations at 0x02024DE8 and 0x0332105C, used
 *     for UserData.listHandSkin (gloves).
 *
 *  | # | Site | RVA = offset | Edit | Why |
 *  |---|------|--------------|------|-----|
 *  | 1 | DSystem.IsGunUnlock(int) entry | 0x0138AD70 | `mov r0,#1; bx lr` | gun tile "owned" predicate (tail-`b` into ContainsKey) |
 *  | 2 | DSystem.IsOwnGlove(string) entry | 0x0137311C | `mov r0,#1; bx lr` | glove icon check by picName |
 *  | 3 | DSystem.CheckHaveGun(int) entry | 0x013837CC | `mov r0,#1; bx lr` | gun-skin ownership (tail-`b` into ContainsKey) |
 *  | 4 | DroneItemCtrl.Refresh inline call | 0x01957C48 | `bl`→`mov r0,#1` | drone tile icon |
 *  | 5 | GloveItemCtrl.Refresh inline call | 0x0197CDA8 | `bl List.Contains`→`mov r0,#1` | glove tile icon |
 *  | 6 | WeaponShopView.IsOwnedItem(int) entry | 0x011F3650 | `mov r0,#1; bx lr` | gun shop button shows Equip/Upgrade, not Buy |
 *  | 7 | DSystem.FixedWrongEquipWeapon branch | 0x0137F708 | `bne`→`b` | keep the equipped gun slot instead of repairing it away |
 *  | 8 | DSystem.GetOwnedGunConfigById gate | 0x0137FD78 | `beq`→`nop` | do not return null for an un-owned gun |
 *  | 9 | DSystem.GetCurEquipedGunBattle inventory gate | 0x01380214 | `beq`→`nop` | same, on the battle-prep path |
 *  | 10 | RaidbossSelectionView.CheckItemLocked entry | 0x011A3B3C | `mov r0,#0; bx lr` | raid-boss items never locked (predicate is inverted) |
 *  | 11 | GlovesView.RefreshButtons inline call | 0x01120F2C | `bl`→`mov r0,#1` | gloves shop Equip button |
 *  | 12 | ControlGloveSkin.RefreshButtons inline call | 0x01674820 | `bl`→`mov r0,#1` | gloves in-battle Equip button |
 *  | 13 | DroneShopView.SetupButtonEquip inline call | 0x0111CDB4 | `bl`→`mov r0,#1` | drone Equip button appears |
 *  | 14 | DroneShopView.SetupButtonUnlock inline call | 0x0111CF74 | `bl`→`mov r0,#1` | drone caption/price label (cosmetic; drop this one freely) |
 *
 * Notes that matter when re-targeting a future build:
 *  * Sites 4, 5, 11–14 are **mid-function** edits. The 4-byte `bl` is replaced by a
 *    complete 4-byte `mov r0,#1` (no `nop` filler needed on ARM32), so the enclosing method
 *    runs on with `r0 = 1` and takes its "owned" path.
 *  * Site 7 replaces `bne` (`1A 00 00 11`) with the unconditional `b` of the same
 *    displacement (`EA 00 00 11`, target 0x0137F754), so an equipped slot is kept even when
 *    the gun id is absent from `inventory`. `nop` would be wrong here — it falls into the
 *    repair block.
 *  * Sites 8 and 9 replace `beq` with the ARM `nop` hint (`E3 20 F0 00`), so control falls
 *    through into the happy path that already sits at the next instruction. Safe because
 *    `DUtil.GetLevelItem` returns a static default level for an un-owned id instead of
 *    throwing or returning null.
 *  * Site 10's predicate returns `!inventory.ContainsKey(id)`, so "never locked" is a
 *    constant **false**, not true.
 *
 * ============================================================================
 * ANCHOR UNIQUENESS
 * ============================================================================
 * 4-byte instruction encodings are useless as anchors (`mov r0,#1` occurs thousands of
 * times), and even the handful of 12-byte prologues are shared (e.g. `IsGunUnlock` and
 * `CheckHaveGun` have identical first instructions). Each site therefore carries a 36- or
 * 48-byte window verified to occur **exactly once** in the 76 MB library, and the window
 * always starts at the offset that will be written, so the match also pins *which*
 * instruction is being replaced. A missing or ambiguous window aborts the patch before a
 * single byte is written.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * `PatchEngine` merges the XAPK splits into one APK first, then a `rawResourcePatch`
 * anywhere in the set forces `ResourceMode.RAW_ONLY`, so `get("lib/armeabi-v7a/
 * libil2cpp.so", true)` resolves the raw-extracted library. Every write is same-length in
 * place (8-over-8 at the five entry sites, 4-over-4 at the nine inline sites — 76 bytes
 * total), so `detectFileChanges()` picks each up via `lastModified` and
 * `ApkUtils.applyTo` overlays them into the rebuilt, re-signed APK.
 *
 * ============================================================================
 * SCOPE
 * ============================================================================
 * * No ads (`../ads/InstantRewardedVideoPatch.kt`), no currency
 *   (`./UnlimitedCurrencyPatch.kt`).
 * * No `DSystem.EnableGod` / `EnablePhoenix` / `showcheat` — those need a runtime companion
 *   `.so` for the cheat methods and are deliberately out of scope.
 * * No arm64 branch: 4.186.0 is armeabi-v7a only.
 *
 * Discovery: a top-level `val … = rawResourcePatch(…)` IS a public static Patch-valued
 * field, which is what `PatchLoader` scans, so list-patches shows exactly one
 * "All items owned" entry for Dead Target.
 */
@Suppress("unused")
val allItemsOwnedPatch = rawResourcePatch(
    name = "All items owned",
    description = "Every gun, skin, glove and drone shows as unlocked, and you can equip any " +
        "of them. Your save file is not changed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        applyItemAnchors(get("lib/armeabi-v7a/libil2cpp.so", true), ARM32_ITEM_ANCHORS)
    }
}

/**
 * One guarded byte-range replacement in `libil2cpp.so`.
 *
 * [anchorHex] is the ORIGINAL 36/48-byte window (must occur exactly ONCE in the library);
 * [replacementHex] is written over the first bytes of that window — 8 bytes at the five
 * function-entry sites, 4 bytes at the nine mid-function sites.
 *
 * Named `ItemAnchor`, not `Anchor`, because this file shares package
 * `app.deadtarget.patches.unlock` with `UnlimitedCurrencyPatch.kt`, which already declares a
 * private top-level `Anchor`. Kotlin reports a hard `Redeclaration` for a second same-named
 * private top-level class in one package. The shape is identical, only the identifier
 * differs; the same reasoning renames [ARM32_ITEM_ANCHORS] / [applyItemAnchors] and is why
 * the stub constants below are named after what they *do* rather than reusing
 * `STUB_INT64`/`STUB_INT32` (the file-private helpers `hex`, `indexOfAll`, `toHex` are
 * legitimately duplicated and deliberately kept byte-identical).
 */
private class ItemAnchor(
    /** Human label used in logs and [PatchException] messages. */
    val label: String,
    /** Virtual address from Il2CppDumper's script.json (= file offset in this ELF). */
    val va: Long,
    /** Exact file offset — identical to [va] for this 32-bit ELF. */
    val fileOffset: Long,
    /** Hex of the ORIGINAL window — must occur exactly once, or the patch aborts. */
    val anchorHex: String,
    /** Hex of the replacement written at that window's first bytes. */
    val replacementHex: String,
)

/**
 * `mov r0,#1 ; bx lr` (E3 A0 00 01 / E1 2F FF 1E) — for the five **function-entry** sites.
 */
private const val BOOL_TRUE = "0100A0E31EFF2FE1"

/**
 * `mov r0,#0 ; bx lr` — site 10 only, the inverted predicate.
 */
private const val BOOL_FALSE = "0000A0E31EFF2FE1"

/**
 * `mov r0,#1` alone, **no `ret`** — for the nine **mid-body** sites. Replaces the 4-byte
 * `bl` that would have consulted the ownership collection, so the enclosing method runs on
 * with `r0 = 1` and takes its "owned" path (at the tile sites it has to load `_iconOwned`
 * and call `SetActive`; at the equip-button sites it has to reach
 * `Btn_Use`/`_btnEquip.gameObject.SetActive(true)`).
 */
private const val BOOL_TRUE_W = "0100A0E3"

/**
 * `b #+0x44` — site 7 only. Replaces the `bne +0x44` in `DSystem.FixedWrongEquipWeapon`
 * with an unconditional branch to the same target (0x0137F754), so an equipped slot is kept
 * even when the gun id is absent from `inventory`.
 */
private const val BRANCH_KEEP_SLOT = "EA000011"

/**
 * ARM `nop` hint (E3 20 F0 00) — sites 8 and 9. Replaces the `beq` "not owned → return
 * null" early-out in the two gun-record resolvers, so control falls through to the happy
 * path already sitting at the next instruction.
 */
private const val NOP_FALLTHROUGH = "00F020E3"

/**
 * The fourteen sites. Every anchor window was verified to occur exactly once in the shipped
 * `libil2cpp.so`; the single hit is the documented file offset in all fourteen cases.
 */
private val ARM32_ITEM_ANCHORS = listOf(
    // ---- Site 1: GUN TILE ICON. Function entry: 0xE92D4070 `push {r4,r5,r6,lr}`, body
    // tail-`b`s into Dictionary<int,int>.ContainsKey at 0x02D454E0. Display only; the
    // equip/use gates are sites 6–10. 8-byte entry write.
    ItemAnchor(
        label = "DSystem.IsGunUnlock -> true (gun tile icon)",
        va = 0x0138AD70,
        fileOffset = 0x0138AD70,
        anchorHex =
            "70402DE964609FE50050A0E10140A0E1" +
                "06608FE00000D6E5000050E30400001A" +
                "4C009FE500009FE7613FF0EB0100A0E3",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 2: GLOVES (primary). Function entry: 0xE92D4070 `push {r4,r5,r6,lr}`.
    // `IsOwnGlove(string glovename)` — a 36-byte window is already unique here.
    ItemAnchor(
        label = "DSystem.IsOwnGlove(string) -> true (gloves: hand-skin picName check)",
        va = 0x0137311C,
        fileOffset = 0x0137311C,
        anchorHex =
            "70402DE980619FE50040A0E10150A0E1" +
                "06608FE00000D6E5000050E30A00001A" +
                "68019FE5",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 3: GUN SKINS. Function entry: 0xE92D4070 `push {r4,r5,r6,lr}`, tail-`b`
    // into the same ContainsKey at 0x02D454E0. Its first 36 bytes are identical to site 1,
    // so the 48-byte window is required.
    ItemAnchor(
        label = "DSystem.CheckHaveGun -> true (gun skins)",
        va = 0x013837CC,
        fileOffset = 0x013837CC,
        anchorHex =
            "70402DE964609FE50050A0E10140A0E1" +
                "06608FE00000D6E5000050E30400001A" +
                "4C009FE500009FE7CA5CF0EB0100A0E3",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 4: DRONES. *** MID-FUNCTION INLINE EDIT *** `bl 0x02D454E0`
    // (Dictionary<int,int>.ContainsKey) inside DroneItemCtrl.Refresh; `ldr r5,[r5,#0xA0]`
    // two instructions earlier pins UserData.droneInventory, `ldr r6,[r6,#0x8]` pins
    // DroneConfig.id. The result is moved to r5 and fed to `SetActive`. 4-byte write.
    ItemAnchor(
        label = "DroneItemCtrl.Refresh inline droneInventory.ContainsKey -> true (drone tile)",
        va = 0x01957C48,
        fileOffset = 0x01957C48,
        anchorHex =
            "24B64FEB206094E50050A0E1000056E3" +
                "0000001A450CD9EB0600A0E10510A0E1" +
                "0020A0E3",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 5: GLOVES TILE ICON. *** MID-FUNCTION INLINE EDIT *** `bl 0x0332105C`
    // (List<int>.Contains) inside GloveItemCtrl.Refresh; `ldr r9,[r6,#0x314]` pins
    // UserData.listHandSkin. Needed on top of site 2 because this tile inlines the test
    // instead of calling IsOwnGlove. The `mov r6,#1` at +0x10 is the game's OWN
    // gloveId==0 "always owned" shortcut, not something this patch writes. 4-byte write.
    ItemAnchor(
        label = "GloveItemCtrl.Refresh inline listHandSkin.Contains -> true (glove tile icon)",
        va = 0x0197CDA8,
        fileOffset = 0x0197CDA8,
        anchorHex =
            "AB9066EB0060A0E1000000EA0160A0E3" +
                "247094E5000057E30000001AEB77D8EB" +
                "0700A0E1",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 6: GUNS SHOP ACTION BUTTON. Function entry: 0xE92D4070 `push {r4,r5,r6,lr}`.
    // Body tail-`b`s into ContainsKey at 0x02D454E0 after `ldr r5,[r5,#0xF4]`
    // (this._data.Inventory). The shop's only ownership predicate (9 call sites); its caller
    // picks the Equip/Upgrade wiring over the Unlock/Buy wiring. 48-byte window because the
    // first 36 bytes are shared with sites 1/3. 8-byte write.
    ItemAnchor(
        label = "WeaponShopView.IsOwnedItem -> true (gun shop button: Equip/Upgrade not Buy)",
        va = 0x011F3650,
        fileOffset = 0x011F3650,
        anchorHex =
            "70402DE954609FE50050A0E10140A0E1" +
                "06608FE00000D6E5000050E30400001A" +
                "3C009FE500009FE7299DF6EB0100A0E3",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 7: GUNS LOADOUT REPAIR. *** MID-FUNCTION BRANCH EDIT *** `bne 0x0137F754`
    // — "owned, keep the equipped slot" — right after `bl 0x02D454E0` + `cmp r0,#0` inside
    // DSystem.FixedWrongEquipWeapon. Replaced with an unconditional `b` to the same target,
    // so the slot is never overwritten with the fallback gun id. Runs on every
    // SetupEquipment(). 4-byte write, no `ret`.
    ItemAnchor(
        label = "DSystem.FixedWrongEquipWeapon -> always keep the equipped gun slot",
        va = 0x0137F708,
        fileOffset = 0x0137F708,
        anchorHex =
            "1100001AF05099E5000055E30000001A" +
                "966DF0EBB05095E5000054E30170A0E3" +
                "02700003",
        replacementHex = BRANCH_KEEP_SLOT,
    ),
    // ---- Site 8: GUN RECORD RESOLVER. *** MID-FUNCTION BRANCH EDIT *** `beq 0x0137FE80`
    // — "not in inventory -> return null" — right after ContainsKey inside
    // DSystem.GetOwnedGunConfigById. Replaced with `nop` so control falls through to the
    // happy path. 4-byte write, no `ret`.
    ItemAnchor(
        label = "DSystem.GetOwnedGunConfigById -> do not return null for an un-owned gun",
        va = 0x0137FD78,
        fileOffset = 0x0137FD78,
        anchorHex =
            "4000000A1C019FE500009FE7000090E5" +
                "741090E5000051E30000001AA86BF0EB" +
                "0400A0E1",
        replacementHex = NOP_FALLTHROUGH,
    ),
    // ---- Site 9: GUN BATTLE-PREP RECORD RESOLVER. *** MID-FUNCTION BRANCH EDIT *** `beq
    // 0x01380314` — "gunId not in UserData.inventory -> return null" — right after
    // `Dictionary<int,int>.ContainsKey(userData.inventory, record.id)` inside
    // DSystem.GetCurEquipedGunBattle. Replaced with `nop` so control falls through to the
    // happy path (`ConfigManager.configUpgradeItem` + `ConfigUpgradeWeapon.GetConfigByIdLevel`).
    // 4-byte write, no `ret`.
    //
    // BEWARE when re-targeting: GetCurEquipedGunBattle contains a SECOND, earlier
    // `ContainsKey` on a different dictionary (userData.equipedItems, offset #0xB0) with the
    // sentinel key -1 at 0x0137FF5C and `beq 0x0138008C` at 0x0137FF64. That guard is NOT an
    // ownership check — it gates a one-shot equip-slot migration block that ends in
    // `Dictionary<int,int>.Add`. Nop'ing it makes the block re-run on a later call and throws
    // `ArgumentException: An item with the same key has already been added. Key: 1`
    // (Dictionary.Add at 0x02D45294) inside GunController.LoadAllGun -> ... -> this method.
    // The ownership gate is the inventory `ContainsKey` at 0x0138020C/0x01380214, patched here.
    ItemAnchor(
        label = "DSystem.GetCurEquipedGunBattle -> do not return null for an un-owned gun",
        va = 0x01380214,
        fileOffset = 0x01380214,
        anchorHex =
            "3E00000A40019FE500009FE7084095E5" +
                "000090E5741090E5000051E30000001A" +
                "806AF0EB",
        replacementHex = NOP_FALLTHROUGH,
    ),
    // ---- Site 10: RAID-BOSS MODE ITEM LOCK. Function entry: 0xE92D4830
    // `push {r4,r5,r11,lr}`, body `bl 0x02D454E0` then inverts the result — the predicate
    // returns !inventory.ContainsKey(id), so the constant is FALSE here, not true.
    // 48-byte window (the entry is shared with other 4-arg helpers). 8-byte write.
    ItemAnchor(
        label = "RaidbossSelectionView.CheckItemLocked -> false (raid-boss items never locked)",
        va = 0x011A3B3C,
        fileOffset = 0x011A3B3C,
        anchorHex =
            "30482DE990509FE50140A0E105508FE0" +
                "0000D5E5000050E30700001A7C009FE5" +
                "00009FE7EFDBF7EB74009FE500009FE7",
        replacementHex = BOOL_FALSE,
    ),
    // ---- Site 11: GLOVES SHOP EQUIP BUTTON. *** MID-FUNCTION INLINE EDIT ***
    // `bl 0x02024DE8` (List<int>.Contains) inside GlovesView.RefreshButtons;
    // `ldr r7,[r5,#0x310]` pins UserData.listHandSkin, `ldr r6,[r9,#0x8]` pins
    // HandSkinRecord.handID. The following `beq 0x011210C4` is the not-owned path that
    // skips the only `Btn_Use.SetActive(true)` (0x01121078) — so the button never appears
    // unless this call reports owned. 4-byte write, no `ret`.
    ItemAnchor(
        label = "GlovesView.RefreshButtons inline listHandSkin.Contains -> true (gloves shop equip)",
        va = 0x01120F2C,
        fileOffset = 0x01120F2C,
        anchorHex =
            "AD0F3CEB000050E36200000A088099E5" +
                "2C049FE500009FE7000090E596ED5EEB" +
                "0060A0E1",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 12: GLOVES IN-BATTLE EQUIP BUTTON. *** MID-FUNCTION INLINE EDIT *** the
    // same algorithm as site 11 in a different class (NGUI ControlGloveSkin, Btn_Use at
    // +0x70). `bl 0x0332105C` then `beq 0x016749BC` selects the not-owned path. NOT
    // optional: equipping from the battle screen never runs GlovesView.RefreshButtons.
    ItemAnchor(
        label = "ControlGloveSkin.RefreshButtons inline listHandSkin.Contains -> true (battle gloves equip)",
        va = 0x01674820,
        fileOffset = 0x01674820,
        anchorHex =
            "0DB272EB000050E36300000A085098E5" +
                "08049FE500009FE7000090E5599F49EB" +
                "0060A0E1",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 13: DRONES SHOP EQUIP BUTTON — ESSENTIAL. *** MID-FUNCTION INLINE EDIT ***
    // `bl 0x02D454E0` (Dictionary<int,int>.ContainsKey) at the start of
    // DroneShopView.SetupButtonEquip; `ldr r7,[r5,#0x8]` is DroneConfig.idItem and the
    // result is passed straight to `SetActive` (`mov r1,r4` at +0x68). 4-byte write.
    ItemAnchor(
        label = "DroneShopView.SetupButtonEquip inline droneInventory.ContainsKey -> true (drone equip button)",
        va = 0x0111CDB4,
        fileOffset = 0x0111CDB4,
        anchorHex =
            "C9A170EB0040A0E1000099E5F8FD5EEB" +
                "0070A0E1000050E30000001AE8F7F9EB" +
                "F06097E5",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 14: DRONES SHOP CAPTION — COSMETIC, AND THE ONE DROPPABLE SITE IN THIS
    // FILE. *** MID-FUNCTION INLINE EDIT *** `bl 0x02D454E0` then `cmp r6,#0` / `beq`
    // selects between two localization slots feeding the *unlock* button caption and its
    // price label — nothing else. Delete this single entry to revert it.
    ItemAnchor(
        label = "DroneShopView.SetupButtonUnlock inline droneInventory.ContainsKey -> true (drone caption, label only)",
        va = 0x0111CF74,
        fileOffset = 0x0111CF74,
        anchorHex =
            "59A170EB50509AE50060A0E1000055E3" +
                "0000001A7AF7F9EB000056E30300000A" +
                "000095E5",
        replacementHex = BOOL_TRUE_W,
    ),
)

/**
 * Applies every [ItemAnchor] to [lib], guarded by a unique-anchor search.
 *
 * Two-phase so a bad build fails with **zero** bytes written:
 *  1. the library is slurped once and every window is searched for, requiring **exactly
 *     one** occurrence;
 *  2. each resolved offset is re-read through a [RandomAccessFile], compared against the
 *     original bytes, and only then overwritten.
 *
 * Every write is the same length as the bytes it replaces — 8-over-8 at the five entry
 * sites, 4-over-4 at the nine mid-function sites, 76 bytes in total — so the patcher's
 * `lastModified`-keyed change diff picks it up.
 */
private fun applyItemAnchors(lib: File, anchors: List<ItemAnchor>) {
    println("Dead Target all items owned: patching ${lib.name} (${lib.length()} bytes)")
    val bytes = lib.readBytes()

    // Phase 1 — resolve every anchor, or abort before touching anything.
    val writes = anchors.map { anchor ->
        val needle = hex(anchor.anchorHex)
        val replacement = hex(anchor.replacementHex)
        val hits = indexOfAll(bytes, needle)
        when {
            hits.isEmpty() -> throw PatchException(
                "Dead Target all items owned: ${anchor.label} — ${needle.size}-byte anchor " +
                    "not found in ${lib.name} (size=${bytes.size}). Expected file offset 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}). " +
                    "Unsupported app version or ABI?",
            )

            hits.size > 1 -> throw PatchException(
                "Dead Target all items owned: ${anchor.label} — ${needle.size}-byte anchor is " +
                    "AMBIGUOUS (${hits.size} occurrences: " +
                    hits.joinToString(", ") { "0x" + it.toString(16) } +
                    "). Refusing to guess — unsupported app version?",
            )
        }
        val at = hits[0].toLong()
        if (at + replacement.size > bytes.size) {
            throw PatchException(
                "Dead Target all items owned: ${anchor.label} — resolved file offset 0x" +
                    "${at.toString(16)} is past end of ${lib.name} (size=${bytes.size}) — " +
                    "app layout changed?",
            )
        }
        val original = needle.copyOf(replacement.size)
        if (!bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size).contentEquals(original)) {
            throw PatchException(
                "Dead Target all items owned: ${anchor.label} — anchor mismatch at VA 0x" +
                    "${anchor.va.toString(16)} (file 0x${at.toString(16)}): expected " +
                    "${toHex(original)} vs found " +
                    "${toHex(bytes.copyOfRange(at.toInt(), at.toInt() + replacement.size))}. " +
                    "libil2cpp.so layout changed — unsupported app version?",
            )
        }
        if (at != anchor.fileOffset) {
            println(
                "Dead Target all items owned: ${anchor.label} — NOTE: unique anchor resolved " +
                    "to 0x${at.toString(16)}, documented file offset is 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}).",
            )
        }
        Triple(anchor, at, replacement)
    }

    // Phase 2 — guarded in-place writes.
    RandomAccessFile(lib, "rw").use { raf ->
        for ((anchor, at, replacement) in writes) {
            val original = hex(anchor.anchorHex).copyOf(replacement.size)
            raf.seek(at)
            val actual = ByteArray(replacement.size)
            raf.readFully(actual)
            if (!actual.contentEquals(original)) {
                throw PatchException(
                    "Dead Target all items owned: ${anchor.label} — original bytes changed " +
                        "between resolve and write at file 0x${at.toString(16)}: expected " +
                        "${toHex(original)} vs found ${toHex(actual)}.",
                )
            }
            raf.seek(at)
            raf.write(replacement)
            println(
                "Dead Target all items owned: VA 0x${anchor.va.toString(16)} " +
                    "(file 0x${at.toString(16)}), ${replacement.size}-byte edit: " +
                    "${toHex(original)} -> ${anchor.replacementHex}  [${anchor.label}]",
            )
        }
    }
}

/** Every offset at which [needle] occurs in [haystack]. */
private fun indexOfAll(haystack: ByteArray, needle: ByteArray): List<Int> {
    if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
    val hits = mutableListOf<Int>()
    var i = 0
    val last = haystack.size - needle.size
    while (i <= last) {
        var j = 0
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) {
            hits.add(i)
            i += needle.size // anchors cannot overlap themselves
        } else {
            i += j + 1
        }
    }
    return hits
}

/** Parses a plain hex string (no separators) into bytes. */
private fun hex(s: String): ByteArray =
    s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** "XX XX XX XX" formatter for mismatch messages. */
private fun toHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

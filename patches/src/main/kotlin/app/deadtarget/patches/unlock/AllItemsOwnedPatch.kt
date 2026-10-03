package app.deadtarget.patches.unlock

import app.deadtarget.patches.shared.Constants.COMPATIBILITY_DEAD_TARGET
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.readBytes

/**
 * Dead Target: Offline Games 3D 4.183.0 (583009063) — All items owned.
 *
 * Dead Target is Unity IL2CPP: the smali layer is SDK plumbing only, every ownership decision
 * lives in `libil2cpp.so` (85,048,024 bytes, arm64-v8a, plaintext `.text`). This patch forces
 * the game's own ownership *reads* to answer "owned", so **everything unlockable reads as
 * owned**: every gun-skin tile is ticked, every glove lights up in the shop and in the
 * in-battle selector, every drone tile renders as owned, and — because the five gun-usability
 * gates below were merged in after device testing — every gun can actually be **selected,
 * equipped and fired**.
 *
 * **Ten sites, ten same-length in-place edits** in one file: five ownership **display** sites
 * (guns tile, gloves, gun skins, drone tile, glove tile — merged in from
 * `analysis/dead-target/notes/cosmetic-unlock-targets.md`) plus five gun **usability** gates
 * merged in from `analysis/dead-target/notes/gun-usable-gate.md` after the shipped patch was
 * falsified on device. Same shape as ../unlock/UnlimitedCurrencyPatch.kt and
 * ../ads/InstantRewardedVideoPatch.kt.
 *
 * ============================================================================
 * THE TEN SITES — five DISPLAY sites (1–5) + five GUN-USABILITY gates (6–10)
 * ============================================================================
 *  | # | What                | Predicate / site                        | VA        | File offset | Write | Kind          |
 *  |---|---------------------|-----------------------------------------|-----------|-------------|-------|---------------|
 *  | 1 | GUNS tile icon      | `DSystem.IsGunUnlock(int)`              | 0x021DB470| 0x021D7470  | 8 B   | entry stub    |
 *  | 2 | GLOVES (primary)    | `DSystem.IsOwnGlove(string)`            | 0x021C9314| 0x021C5314  | 8 B   | entry stub    |
 *  | 3 | SKINS               | `DSystem.CheckHaveGun(int)`             | 0x021D5BB4| 0x021D1BB4  | 8 B   | entry stub    |
 *  | 4 | DRONES              | inline `droneInventory.ContainsKey`     | 0x02635630| 0x02631630  | 4 B   | **mid-body**  |
 *  | 5 | GLOVES tile icon    | inline `listHandSkin.Contains`          | 0x0264D228| 0x02649228  | 4 B   | **mid-body**  |
 *  | 6 | GUNS shop button    | `WeaponShopView.IsOwnedItem(int)`       | 0x020A8708| 0x020A4708  | 8 B   | entry stub    |
 *  | 7 | GUNS loadout wipe   | `DSystem.FixedWrongEquipWeapon()` branch| 0x021D290C| 0x021CE90C  | 4 B   | **branch**    |
 *  | 8 | GUNS record resolve | `GetOwnedGunConfigById(int)` branch     | 0x021D2DB0| 0x021CEDB0  | 4 B   | **branch**    |
 *  | 9 | GUNS battle resolve | `GetCurEquipedGunBattle(int)` branch    | 0x021D30E4| 0x021CF0E4  | 4 B   | **branch**    |
 *  |10 | RAID-BOSS item lock | `RaidbossSelectionView.CheckItemLocked` | 0x0206B9A8| 0x020679A8  | 8 B   | entry stub    |
 *
 * ⚠️ Sites 1–3, 6 and 10 overwrite a **function entry**, so the stub is `mov w0,#1 ; ret`
 * (site 10 inverts it: `mov w0,#0 ; ret`, because it returns *locked*, not owned). Sites 4–5
 * are **mid-function inline edits**: they replace a single 4-byte `bl` instruction and must
 * NOT be given a `ret`. See SITES 4 AND 5 for the three substitute proofs that replace the
 * entry-prologue test there.
 *
* ⚠️ Sites 7–9 are **neither** of those: they are 4-byte **condition-code forcing** edits on
* one conditional branch each, joining sites 4–5 as the places where a 36-byte verification
* window carries a 4-byte write. See SITES 6 TO 10 for all three. Anchor length and write length
* are deliberately independent axes here (36 B verified / 4 B written at sites 4, 5, 7, 8, 9), so
* do not "normalise" one to the other.
 *
 * Original 36-byte anchors (verbatim from the shipped library; each occurs exactly once):
 *
 *  1 IsGunUnlock  0x021D7470  FE 57 BE A9 F4 4F 01 A9 75 7A 01 B0 A8 2E 50 39
 *                          F3 03 01 2A F4 03 00 AA C8 00 00 37 E0 5E 01 F0
 *                          00 AC 40 F9
 *                  →           20 00 80 52 C0 03 5F D6              (8 B)
 *  2 IsOwnGlove   0x021C5314  FE 0F 1D F8 F6 57 01 A9 F4 4F 02 A9
 *                          F6 7A 01 F0 95 5F 01 90 C8 1A 50 39 B5 7A 45 F9
 *                          F4 03 01 AA F3 03 00 AA
 *                  →           20 00 80 52 C0 03 5F D6              (8 B)
 *  3 CheckHaveGun 0x021D1BB4  FE 57 BE A9 F4 4F 01 A9 95 7A 01 F0
 *                          A8 4E 50 39 F3 03 01 2A F4 03 00 AA C8 00 00 37
 *                          20 5F 01 B0 00 AC 40 F9
 *                  →           20 00 80 52 C0 03 5F D6              (8 B)
 *  4 drone inline 0x02631630  7C 43 42 94 68 22 40 F9 08 08 00 B4
 *                          01 00 00 12 E0 03 08 AA E2 03 1F AA
 *                          73 F1 8A 94 60 1E 40 F9 40 07 00 B4
 *                  →           20 00 80 52                          (4 B)
 *  5 glove inline 0x02649228  F7 71 56 94 02 00 00 14 20 00 80 52 68 26 40 F9
 *                          68 03 00 B4 01 00 00 12 E0 03 08 AA E2 03 1F AA
 *                          73 92 8A 94
 *                  →           20 00 80 52                          (4 B)
 *  6 IsOwnedItem  0x020A4708  FE 57 BE A9 F4 4F 01 A9 F5 83 01 F0 A8 82 6E 39
 *                          F3 03 01 2A F4 03 00 AA C8 00 00 37 80 68 01 D0
 *                          00 AC 40 F9
 *                  →           20 00 80 52 C0 03 5F D6              (8 B)
 *  7 FixedWrong…  0x021CE90C  40 01 00 37 68 D2 40 F9 88 09 00 B4 00 89 40 F9
 *                          20 09 00 B4 63 03 40 F9 DF 02 00 71 42 17 9A
 *                          1A E1 03 14 2A
 *                  →           0A 00 00 14                          (4 B)
 *  8 GetOwnedGun… 0x021CEDB0  C0 06 00 36 28 5F 01 D0 08 E9 46 F9 00 01 40 F9
 *                          08 E0 40 B9 48 00 00 35 02 09 F3 97 E0 03 13
 *                          2A E1 03 1A AA
 *                  →           1F 20 03 D5                          (4 B)
 *  9 GetCurEqu…   0x021CF0E4  A0 06 00 36 28 5F 01 B0 08 E9 46 F9 93 12 40 B9
 *                          00 01 40 F9 08 E0 40 B9 48 00 00 35 34 08 F3 97
 *                          E0 03 13 2A
 *                  →           1F 20 03 D5                          (4 B)
 * 10 CheckItemLock 0x020679A8 FE 57 BE A9 F4 4F 01 A9 F4 85 01 90 75 6A 01 B0
 *                          88 46 65 39 B5 56 46 F9 F3 03 01 2A 28 01 00 37
 *                          60 6A 01 F0
 *                  →           00 00 80 52 C0 03 5F D6              (8 B)
 *
 * Every write is **same-length in place** — 8-over-8 at sites 1–3, 6, 10, 4-over-4 at sites
 * 4–5 and 7–9 — which is what the patcher's `lastModified`-keyed change diff detects (see
 * DELIVERY). Only 8 or 4 bytes of each 36-byte window are written; the other 28 / 32 are
 * verified and left untouched, so file length never changes.
 *
 * ============================================================================
 * VA → FILE OFFSET: the +0x4000 trap
 * ============================================================================
 * ⚠️ **Il2CppDumper's `script.json` Address (and dump.cs's `VA:`) is a VIRTUAL address.**
 * Dead Target's executable LOAD segment maps `file 0x1C1273C -> VA 0x1C1673C`, i.e.
 * **delta = +0x4000**, so **file offset = VA − 0x4000**. Re-derived here from the ELF
 * program headers of the shipped library (PT_LOAD flags=R E, `p_offset=0x1C1273C`,
 * `p_vaddr=0x1C1673C`, `p_filesz=0x2F575E4`; the only other PT_LOADs are the R and RW
 * segments). dump.cs's own `Offset:` column agrees independently at site 1:
 * `// RVA: 0x21DB470 Offset: 0x21D7470 VA: 0x21DB470`. Writing at the raw VA lands
 * **+16 KB past** the intended instruction and silently corrupts the library. Same trap
 * documented in both sibling Dead Target patches.
 *
* ⚠️ That delta is **specific to the executable segment**. The other three PT_LOADs have
 * delta 0x0 / +0x8000 / +0xC000, so it must never be applied to `.data` / `.bss`. All ten
 * sites here live in the `il2cpp` section (file `0x1F78B28 … 0x4B6790C`), which carries the
 * same `VA − file = +0x4000` — re-checked per site against the shipped library, all ten
 * agree (VA − file = 0x4000 for every one).
 *
 * ============================================================================
 * ROOT CAUSE — WHY GUNS SHOWED OWNED BUT WOULD NOT LOAD (fixed on device)
 * ============================================================================
 * **The original version of this file shipped a claim that device testing disproved.** It said
 * that forcing `DSystem.IsGunUnlock` "is what makes every gun selectable and equippable", and
 * described the patch as covering equippable guns. **That was wrong, and the reason is
 * worth writing down so nobody re-derives it.**
 *
 * `IsGunUnlock` has exactly **five** callers in the whole 85 MB library (re-derived here with
 * an exhaustive BL-target scan of the executable LOAD segment — 1,554,430 `bl` instructions,
 * 69,345 distinct targets — reproducing the five branch VAs listed at SITE 1 below). **None of
 * the five is on the equip, loadout, battle-preparation or battle-spawn path.** Its only two UI
 * consumers are `GameObject.SetActive` calls inside `WeaponItemCtrl.Init` / `::Refresh`:
 *
 * ```
 * 02668a5c: bl   0x21db470        ; DSystem.IsGunUnlock(idGun)   -> w21
 * 02668ac4: and  w1, w21, #1
 * 02668ac8: mov  x0, x8           ; this._iconOwned
 * 02668ad0: bl   0x48f1c14        ; GameObject.SetActive         <-- the ONLY consumer
 * ```
 *
 * Two verified negatives close the case: neither `WeaponItemCtrl.Init` nor `::Refresh` ever
 * loads `_itemBtn` (`this+0x80`) and neither calls `UnityEngine.UI.Selectable.set_interactable`;
 * and `WeaponItemCtrl.OnClick` (VA `0x02669190`) has **no ownership guard at all**, so tapping
 * a tile always worked. The observable device symptom was therefore exactly: *gun shows as
 * owned → tapping it selects it → the action button is wired to buy/unlock → any loadout you
 * manage to build is wiped before the next battle.*
 *
 * ⇒ **Site 1 drives the inventory/shop tile DISPLAY only. Equipping and firing a gun are gated
 * separately, by sites 6–10 below.** Those five gates were added to fix exactly that, and
 * everything this file previously said about guns being equippable now rests on them instead of
 * on site 1.
 *
 * ============================================================================
 * SITE 1 — `DProject.DSystem.IsGunUnlock(int idGun)` (GUN TILE ICON — display only)
 * ============================================================================
 *  signature : bool DProject_DSystem__IsGunUnlock (DProject_DSystem_o* __this, int32_t idGun,
 *              const MethodInfo* method);
 *  VA        : 0x021DB470
 *  file off. : 0x021D7470
 *
 * Word 0 is a genuine function entry: on disk `FE 57 BE A9` = LE word **0xA9BE57FE** =
 * `stp x21,x20,[sp,#-32]!`, i.e. the standard frameless-leaf prologue that IL2CPP emits for
 * a small method. The remaining anchor words decode as the game's own frame setup and
 * argument marshalling — `0xA9014FF4` `stp x20,x19,[sp,#16]`, `0xB0017A75` `adrp x21`,
 * `0x39502EA8` `ldr x8,[x21,#0xBA8]` (metadata init guard), and crucially
 * **`0x2A0103F3` = `mov x19,x1` at +0x10**, which is `idGun` — proof the anchor really is
 * the top of `IsGunUnlock` and not a lookalike prologue in a neighbouring method.
 *
 * `IsGunUnlock` is not a flag read: its body *is* the ownership test, and it is
 * `UserData.inventory.ContainsKey(idGun)` — **not** a `gunList` scan. Re-decoded from the
 * shipped bytes: `ldr x8,[this+0x1A0]` (the UserData holder field, same `#0x1A0` offset the
 * currency getters in ../unlock/UnlimitedCurrencyPatch.kt read) → `ldr x0,[x8,#0xE8]` =
 * `UserData.inventory`, a `Dictionary<int,int>` → `mov w1,w19` → frame teardown → a **tail**-`b`
 * to `0x36C6420`, the shared generic `Dictionary<int,int>.ContainsKey(int)`. The last words of
 * the window are exactly that shape: `+0x50 0xF9400102` `ldr x2,[x8]`,
 * `+0x54 0xA8C257FE` `ldp x30,x21,[sp],#32` (frame torn down first), then
 * `+0x58 0x1453ABD6` `b 0x36C6420` — so the function's own return value is
 * `inventory.ContainsKey(idGun)`.
 *
 * (Correction to an earlier draft of this file, which described this as
 * `List<int>.Contains` over a `gunList` backing array. `0x36C6420` is provably the *shared
 * generic* `Dictionary<int,int>.ContainsKey` — it is the very same tail target site 3 uses at
 * `0x021D5C0C` — so sites 1 and 3 are byte-for-byte the same predicate at two entry points.)
 *
 * Forcing it to `true` therefore makes every gun id read as owned **through this one entry
 * point** — but "through this one entry point" is the whole limit: the five callers listed
 * below are the complete consumer set, and they are display/cleanup, not equip.
 *
 * The call graph was established by an **exhaustive BL-target scan of the entire executable
 * LOAD segment** (every 4-byte word decoded as a BL, sign-extended imm26 × 4 added back to
 * the branch VA). Exactly **5** call sites target VA 0x021DB470 — no more, no fewer — and
 * all five resolve to methods named in dump.cs:
 *
 *  | Caller                                                       | Branch VA | dump.cs RVA of containing method |
 *  |--------------------------------------------------------------|-----------|----------------------------------|
 *  | WeaponItemCtrl.Init(ItemConfig,int) — the inventory/loadout   | 0x02668A5C| 0x2668860 |
 *  | WeaponItemCtrl.Refresh(int) — the inventory/loadout           | 0x0266955C| 0x26693C8 |
 *  | DSystem.CheckRestoreAllGunHasSkin()                           | 0x021DB930| 0x21DB4D0 |
 *  | DSystem.RemoveGun(int)                                        | 0x021DB42C| 0x21DB394 |
 *  | ConfigLazyEvent4BattlePassRecord.GetBattlePassRewardId(...)   | 0x023C45FC| 0x23C43D4 |
 *
 *  (Branch-VA list re-measured in this pass — matches the table above exactly. For contrast,
 *  the same scan finds 32 callers of `CheckHaveGun` and 9 of `WeaponShopView.IsOwnedItem`.)
 *
 * Per-caller consequence:
 *  - **`WeaponItemCtrl::Init` / `::Refresh` are the point of site 1.** The boolean feeds
 *    `and w1,w21,#1` and then `GameObject.SetActive` on `this._iconOwned` — the per-slot
 *    "is this gun owned" flag that drives the tile's owned icon. Forcing it true makes **the
 *    tile show as owned, and nothing else**; it does not enable, unlock or equip anything. See
 *    ROOT CAUSE above and sites 6–10 below for the equip/use gates it does not cover.
 *  - `DSystem::CheckRestoreAllGunHasSkin` — a save-repair routine; it will now consider every
 *    gun owned. Harmless (see `RemoveGun` below).
 *  - `DSystem::RemoveGun` — used as a `tbz w0,#0` guard before `inventory.Remove(idGun)`.
 *    Forcing the predicate true lets the removal proceed for ids that are not in the
 *    dictionary. **Verified safe:** `Dictionary.Remove` on an absent key is a documented no-op
 *    (no exception, no index shift), so this cannot corrupt `inventory` or the save file. It is
 *    also the pre-existing behaviour for a gun you actually own, which the game calls all
 *    the time.
 *  - `ConfigLazyEvent4BattlePassRecord::GetBattlePassRewardId` — **the one behavioural side
 *    effect**: battle-pass reward selection also sees everything as owned, so it may skip
 *    reward ids it would otherwise offer. Cosmetic and confined to battle-pass reward
 *    choice; nothing crashes and no currency is granted by this patch. Site 3 lands on the
 *    other leg of this same AND — see BATTLE-PASS CONSEQUENCE.
 *
 * ============================================================================
 * SITE 2 — `DProject.DSystem.IsOwnGlove(string glovename)` (GLOVES) — entry stub
 * ============================================================================
 *  VA        : 0x021C9314
 *  file off. : 0x021C5314
 *  signature : bool DProject_DSystem__IsOwnGlove (DProject_DSystem_o* __this,
 *              Il2CppString* glovename, const MethodInfo* method);
 *
 * Word 0 is a genuine entry prologue: on disk `FE 0F 1D F8` = LE word **0xF81D0FFE** =
 * `str x30,[sp,#-0x30]!`. The frame then continues `stp x22,x21,[sp,#0x10]` /
 * `stp x20,x19,[sp,#0x20]`, an `adrp`-pair and the usual metadata-init guard flag test
 * (`ldr w8,[x22,#0x406]` / `tbnz w8,#0`).
 *
 * **Argument-register proof: `0xF40301AA` = `mov x20,x1` at VA 0x021C9330 (entry + 0x1C).**
 * `x1` is the first declared parameter, i.e. `Il2CppString* glovename` (`mov x19,x0` at
 * +0x20 is `this`). This is what distinguishes the anchor from the other prologues sharing
 * those first 12 bytes — and it is the exact analogue of the `mov x19,x1` proof used at
 * site 1. Note the 16- and 20-byte windows are **ambiguous (2 hits)**: the other hit is
 * `DSystem.GetSkinIndex(string,string)` (VA 0x021C91C0), a near-identical two-`Il2CppString*`
 * method. Only 24 bytes and up are unique, so 36 is not gold-plating here — see ANCHOR
 * UNIQUENESS.
 *
 * **Not a dead end:** first 8 bytes `FE0F1DF8F65701A9` ≠ `20008052C0035FD6`.
 *
 * Body (verified by disassembly): resolves the `ConfigHandSkin` config table and then walks
 * `UserData.listHandSkin` comparing `picName`. A genuine predicate, not a constant.
 *
 * **12 call sites, all reads.** Same exhaustive BL scan as site 1.
 *
 *  | # | Branch VA | Containing method | Consequence |
 *  |---|---|---|---|
 *  | 1 | 0x0200A5F0 | `GlovesView.<get_lsGloveSkinRecord>b__22_0(HandSkinRecord)` | LINQ filter building the **owned-gloves list** → all gloves listed. ✔ intended |
 *  | 2 | 0x021FC7B4 | `DSystem.CheckAlreadyHaveEquipment(int rewardId)` | Offers/packs show rewards as already owned → suppresses duplicates. Cosmetic. |
 *  | 3 | 0x021EF204 | `DSystem.GetListGlovesByCondition(bool,int)` | Glove reward-pool filter → owned gloves drop out. Cosmetic. |
 *  | 4 | 0x021C8D84 | `DSystem.canSkipPromoPack(...)` | Promo-pack "already own it" → skips re-offering. Cosmetic. |
 *  | 5 | 0x023ADE68 | `LazyEventController.GetGachaRewardFrom3(...)` | Glove gacha pool filter → fewer duplicate drops. Cosmetic. |
 *  | 6 | 0x0240E20C | `ControlGloveSkin.RefreshButtons(HandSkinRecord)` | **In-battle glove selector buttons** → every glove selectable. ✔ intended |
 *  | 7 | 0x0241B590 | `ItemDailyBonus.CanSkipNonComsumableReward(ref,ref)` | Daily-bonus "already have it". Cosmetic. |
 *  | 8 | 0x02657C1C | `ItemDailyBonusCtrl.CanSkipNonComsumableReward(ref,ref)` | UI twin of #7. Cosmetic. |
 *  | 9 | 0x02BC0024 | `GlovesView.RefreshButtons(HandSkinRecord)` | **Gloves shop buy/equip buttons** → owned branch. ✔ intended |
 *  | 10 | 0x02640FA4 | `LegacyCollectionCtrl.Init()` | Legacy-collection UI init. Read. |
 *  | 11 | 0x02053F98 | `ProgressRoadHelper.CanReceiveReward(int)` | Progress-road reward availability. Read. |
 *  | 12 | 0x02637664 | `BattlePassNewUserOfferCtrl.ShowPackId(int)` | BP new-user offer display. Read. |
 *
 * No caller mutates or consumes anything — no `Remove*`, no `Delete*`, no collection write.
 *
 * ============================================================================
 * SITE 3 — `DProject.DSystem.CheckHaveGun(int idGunRecommend)` (SKINS) — entry stub
 * ============================================================================
 *  VA        : 0x021D5BB4
 *  file off. : 0x021D1BB4
 *  signature : internal bool DProject_DSystem__CheckHaveGun (DProject_DSystem_o* __this,
 *              int32_t idGunRecommend, const MethodInfo* method);
 *
 * Word 0 is a genuine entry prologue: on disk `FE 57 BE A9` = LE word **0xA9BE57FE** =
 * `stp x30,x21,[sp,#-0x20]!`, the frameless-leaf frame — the same word 0 as site 1.
 *
 * **Argument-register proof: `0xF303012A` = `mov w19,w1` at VA 0x021D5BC4 (entry + 0x10).**
 * `w1` is `idGunRecommend` (`mov x20,x0` at +0x14 is `this`).
 *
 * **Not a dead end:** first 8 bytes `FE57BEA9F44F01A9` ≠ `20008052C0035FD6`.
 *
 * The body is a read of two fields then a **tail branch**:
 * `ldr x8,[x20,#0x1A0]` (`DSystem+0x1A0` = UserData) → `ldr x0,[x8,#0xE8]`
 * (`UserData+0xE8` = `inventory`, a `Dictionary<int,int>`) → `mov w1,w19` → frame teardown →
 * `b 0x036C6420`, i.e. `Dictionary<int,int>.ContainsKey(int)`. That target is the **same**
 * shared generic instantiation site 1 tail-branches to (`0x021DB4C8: b 0x36C6420`) — so
 * `CheckHaveGun` and `IsGunUnlock` are byte-for-byte the same predicate
 * (`inventory.ContainsKey(id)`) at two different entry points.
 *
 * **Why this is the skin gate (evidence chain, all byte-verified):** `SkinData`
 * (TypeDefIndex 1566) is `{ int GunID @0x10; int CurrentIndex @0x14; Dictionary<int,int>
 * PartInfo @0x18 }`. `SkinData.IsEnable(int skinIndex)` @ `0x024B1A44` resolves
 * `ConfigWeapon.GetConfigByID(this.GunID)` and then **tail-branches to
 * `DSystem.CheckHaveGun(GunID)`** — `0x024B1D44: ldr w1,[x19,#0x10]` (this.GunID),
 * `0x024B1D5C: b 0x21D5BB4`. (When the gun config cannot be resolved it instead returns
 * `true` already, `0x024B1D60: mov w0,#1` — verified.) And `GunSkinItemCtrl.UpdateStatus`
 * (VA `0x0264D6DC`) computes its owned flag as `SkinData.IsEnable(_skinId)` then
 * **overwrites it with `DSystem.CheckHaveGun(gunId)`** at VA `0x0264D8CC`, before
 * `_iconOwned.SetActive(w20 & 1)` at `0x0264D8E4`. `WeaponSkinView.Refresh()` reaches it at
 * `0x020B02EC`.
 *
 * **⇒ In Dead Target, gun-skin ownership IS gun ownership.** That is exactly why site 3 is
 * needed and site 1 alone is not enough: site 1 covers `IsGunUnlock`, and this skin UI path
 * calls the *other* copy of the same predicate.
 *
 * **32 call sites, all reads** — the same predicate site 1 already forces, so no new
 * behaviour class appears, with three families worth naming:
 *  - **`WeaponSkinView.Refresh`, `GunSkinItemCtrl.UpdateStatus`** — the reason to patch. Every
 *    gun-skin tile shows the owned tick. ✔
 *  - **`WeaponItemCtrl.Init/Refresh`, `SetupCharm`, gun-trial lists** — the same predicate
 *    site 1 already forces via `IsGunUnlock`. No new behaviour.
 *  - **Sale / offer surfaces** (`DSystem.IsGunSale`, `ControlMap2D.ShowSaleGun`,
 *    `ControlMapView.ShowSaleGun`, `ConfigOfferBaseOnGun.*`,
 *    `ConfigWeaponSaleIAP.PassConditionWeapon`, `ShowAdAtLose.ShowItemByLoseCount`, the
 *    `ControlPointMap2D*`/`ControlMap2D` timers and notices) — stop advertising guns you
 *    "already own". Cosmetic and self-consistent.
 *  - **Quest / medal / notice reads** (`QuestManager.IsMatchCondition`,
 *    `MedalSystemHelper.GetFeatGoText`, `UserData.GetWeaponNoticeText`,
 *    `MissionInfoView.RefreshBtnRecommend` / `OnBuyClick`) — read-only.
 *
 * One real, if minor, functional change: `GunTrainManager.GetListGunCanTrial` /
 * `CreateGunTrialMission` shrink, i.e. the gun-trial feature becomes largely inert. Nothing
 * needs trialling when everything is owned, and this is the same trade site 1 already made.
 *
 * ============================================================================
 * SITES 4 AND 5 — MID-FUNCTION INLINE EDITS, NOT ENTRY STUBS
 * ============================================================================
 * ⚠️ **Read this before copying either site.** Neither of these is a function entry, so the
 * argument used at sites 1–3 — "word 0 is a genuine prologue" — **does not apply and is not
 * used here**. Overwriting a mid-body offset with `mov w0,#1 ; ret` would return from the
 * middle of `Refresh` with an unbalanced stack frame and a live prologue left unexecuted; it
 * is exactly the bug an entry-stub rationale invites. Instead each site replaces **one 4-byte
 * `bl` word with the 4-byte `mov w0,#1`**, which is stack-neutral: the enclosing method runs
 * from its own entry exactly as before, its frame is pushed and popped normally, and only the
 * boolean this one call would have returned is forced.
 *
 * The reason they are mid-body at all: `DSystem.IsOwnedDrone()` takes **no id parameter** and
 * `GloveItemCtrl` does not call `IsOwnGlove` — so the IL2CPP compiler **inlined** the
 * per-item collection test into each tile's `Refresh`. There is no callable entry to stub.
 * (The alternative "predicates" were evaluated and rejected: see VERIFIED DEAD ENDS AND
 * REJECTED TARGETS.)
 *
 * ---- SITE 4 — DRONES: `DroneItemCtrl.Refresh` inline `droneInventory.ContainsKey` ----
 * Containing method: `DroneItemCtrl` (TypeDefIndex 2249), `Refresh(int selectedIndex)`
 * VA `0x02635550`; the site is at method + 0xE0.
 *
 * Four substitute proofs that this `bl` is the right instruction, none of which is a
 * prologue argument:
 *
 * 1. **Word 0 decodes as a `BL` to the shared generic.** LE `0x9442437C` → `bl #+0x1090DF0`
 *    → target **`0x036C6420`**, which is provably the shared generic
 *    `Dictionary<int,int>.ContainsKey(int)`: it is the *same* function site 1
 *    (`0x021DB4C8: b 0x36C6420`), site 3 (`0x021D5C0C: b 0x36C6420`) and
 *    `SkinData.isOwnSkin` (`0x024B27EC: b 0x36C6420`) tail-branch to. Re-decoded from the
 *    library bytes, not taken on trust.
 * 2. **The collection is pinned by the preceding load.** `0x02635618: ldr x0,[x9,#0xF0]`
 *    (re-decoded as `0xF9407920`) = `UserData+0xF0` =
 *    `public Dictionary<int,int> droneInventory`, whereas guns use `UserData+0xE8 inventory`
 *    and gloves `UserData+0x488 listHandSkin`. There is only one `droneInventory` read in
 *    this method.
 * 3. **The id argument is in `w1`, as `ContainsKey(int)` requires.**
 *    `0x02635628: ldr w1,[x8,#0x10]` = `DroneConfig.id`. The instruction before the `bl`
 *    (`0x0263562C: ldr x2,[x9]`) is the method-table argument, i.e. the generic shape.
 * 4. **The result flows straight into `_iconOwned.SetActive`.**
 *    `0x02635634: ldr x8,[x19,#0x40]` = `this._iconOwned`, `0x0263563C: and w1,w0,#1`
 *    consumes the bool, `0x02635648: bl 0x048F1C14` = `GameObject.SetActive`. Forcing
 *    `w0 = 1` therefore changes the tile's owned icon and nothing else in the method.
 *
 * `DroneItemCtrl.Refresh` has exactly one caller, `DroneShopView.RefreshDroneItems()` at
 * branch VA `0x02BBC564` (re-decoded, confirmed). `droneInventory` itself is never written by
 * this patch, so the owned-drone dictionary and the save file are untouched.
 * ⚠️ `DroneItemCtrl.OnClick` (VA `0x026353D0`, reached through a Unity button delegate, so
 * it has no BL caller) was disassembled and contains **no ownership guard** — it fires a
 * `DroneShopUIActionEvent` query and navigates. Clicking a now-"owned" tile therefore routes
 * to the normal shop flow rather than a buy flow. That is the desired outcome, but it means
 * site 4 changes **routing, not just an icon** — see HONEST SCOPE.
 *
 * ---- SITE 5 — GLOVES tile icon: `GloveItemCtrl.Refresh` inline `listHandSkin.Contains` ----
 * Containing method: `GloveItemCtrl` (TypeDefIndex 2292), `Refresh(int selectedIndex)`
 * VA `0x0264D178`; the site is at method + 0xB0.
 *
 * **Site 2 alone does not light up the per-glove shop tile**, because `GloveItemCtrl` inlines
 * the same test instead of calling `IsOwnGlove`. If "every glove tile shows as owned" is
 * required, this is the second glove site; included for that reason.
 *
 * Substitute proofs, same shape as site 4:
 *
 * 1. **Word 0 decodes as a `BL` to `List<int>.Contains`.** LE `0x945671F7` →
 *    `bl #+0x159C7DC` → target **`0x03BE9A04`**, the shared generic `List<int>.Contains(int)`
 *    — provably the same function `DSystem.IsOwnGlove(int)` tail-branches to
 *    (`0x021DB2C0: b 0x3be9a04`). Re-decoded from the library bytes.
 * 2. **The collection is pinned by the preceding load.** `0x0264D210: ldr x0,[x8,#0x488]`
 *    (re-decoded as `0xF9424500`) = `UserData+0x488` = `listHandSkin` (`List<int>`).
 * 3. **The id argument is in `w1`.** `0x0264D220: ldr w1,[x19,#0x70]` = `this._gloveId`;
 *    `0x0264D224: ldr x2,[x8]` is the method-table argument again.
 * 4. **The result flows into `_iconOwned.SetActive`.** `0x0264D234: ldr x8,[x19,#0x48]` =
 *    `this._iconOwned`, `0x0264D23C: and w1,w0,#1`, `0x0264D248: bl 0x048F1C14`.
 *
 * `GloveItemCtrl.Refresh` has exactly one caller, `GlovesView.RefreshGloveItems()` at branch
 * VA `0x02BBF5B0` (re-decoded, confirmed). No write to `listHandSkin`. Read-only. ✔
 *
 * Note the surrounding code already contains a literal `mov w0,#1` at `0x0264D230` — the
 * game's own "gloveId == 0 ⇒ owned" shortcut (`cbz w21` at `0x0264D1F8` branches there).
 * That is why this anchor's hex contains `20008052` at offset **+0x08**. Harmless: the
 * replacement is written at +0x00 and the anchor is only ever compared, never written.
 *
 * ============================================================================
 * SITES 6 TO 10 — GUN USABILITY: TWO ENTRY STUBS, THREE CONDITION-CODE EDITS
 * ============================================================================
 * These are the five gates that sit between "the tile shows as owned" and "the gun can be
 * equipped and fired". Every one of them reads the **same** collection as site 1 —
 * `UserData.inventory` (`Dictionary<int,int>` at `UserData+0xE8`) — through a *different* call
 * path, which is exactly why patching one copy of the predicate is not enough. Source:
 * `analysis/dead-target/notes/gun-usable-gate.md`.
 *
 * Two mechanical kinds live here, and they must not be conflated:
 *  - **Sites 6 and 10 are entry stubs** (`mov w0,#1 ; ret` / `mov w0,#0 ; ret`): overwrite a
 *    function prologue, same as sites 1–3, stack never touched.
 *  - **Sites 7, 8 and 9 are mid-function branch edits**: they replace ONE 4-byte conditional
 *    branch word. Never give these a `ret` — the enclosing method must run to completion, or
 *    its frame is left unbalanced. All three carry a **36-byte verification window with a 4-byte
 *    write**, the same axis separation sites 4–5 already use.
 *
 * ---- SITE 6 — `WeaponShopView.IsOwnedItem(int idItem)` — entry stub, always owned ----
 *  VA 0x020A8708 · file 0x020A4708 · write 8 B (entry) · anchor 36 B
 *
 * Genuine entry (`0xA9BE57FE` `stp x30,x21,[sp,#-0x20]!`; `mov w19,w1` = idItem at +0x10,
 * `mov x20,x0` = this at +0x14). Body: `ldr x0,[x20,#0x1E8]` → null check →
 * `mov w1,w19` → tail-`b 0x36C6420`, i.e. `Dictionary<int,int>.ContainsKey`. Field `0x1E8` is
 * `WeaponShopView._data.Inventory` (`_data` is a by-value copy of `UIState.WeaponShopData` at
 * `+0x1D8`, `Inventory` at `Data+0x10`), which `UIState.UpdateWeaponShopData` fills straight
 * from `UserData.inventory` (`0x02B6786C ldr x8,[x8,#0xe8]` → `0x02B67870 str x8,[x19,#0x38]`).
 * **So this is byte-for-byte the same predicate as site 1, reached without a call.**
 *
 * It is the shop's **only** ownership predicate: 9 BL callers, all gun-shop UI, re-measured
 * here (`SetStatusButton` 0x020A7B38, `SetupCharm` 0x020A8198, `SetupBtnForProgressRoadWeapon`
 * 0x020A89D4, `GetCurPrice` 0x020A90E0, `SetupBtnForCraftWeapon` 0x020A9838,
 * `SetupBtnForNormalWeapon` 0x020AA0C8, `GetCurrentBulletText` 0x020AAC90, `BuyAmmo` 0x020AAFE8,
 * `GetCurTypePrice` 0x020AC034). The one that matters is `SetupBtnForNormalWeapon`:
 *
 * ```
 *  020aa0c8: bl   0x20a8708            ; IsOwnedItem(ItemConfig.id)      <-- site 6
 *  020aa0cc: tbz  w0, #0x0, 0x20aa520  ; NOT owned -> buy/unlock button wiring
 *  020aa0d0: ldr  x0, [x19, #0xf0]     ; owned:     _lbGunLocked
 *  020aa0ec: bl   0x48f1c14            ;   SetActive(false)
 * ```
 *
 * ⇒ site 6 picks the **Equip/Upgrade** button layout instead of the **Unlock/Buy** one. Without
 * it a gun that site 1 already displays as owned still gets the buy button.
 *
 * ---- SITE 7 — `DSystem.FixedWrongEquipWeapon()` — unconditional "keep the slot" (4 B) ----
 *  VA 0x021D290C · file 0x021CE90C · write 4 B · anchor 36 B
 *
 * ```
 *  021d2908: bl   0x36c6420       ; inventory.ContainsKey(equipedItems[slotKey])
 *  021d290c: tbnz w0, #0x0, 0x21d2934   ; OWNED -> keep the slot     <-- site 7
 *  021d2910..021d2930:                   ; the repair: equipedItems[slotKey] = fallback 1 or 2
 *  021d2934: sub  w22, w22, #1
 * ```
 *
 * **This is the gate that actually ate the equip.** The routine runs on *every*
 * `SetupEquipment()` (11 BL callers, re-measured: `DSystem.SetupEquipment` 0x021D262C,
 * `MissionInfoView` 0x02024428, `EndlessView` 0x0201FAF8, `MeleeView` 0x02020A80,
 * `RaidBossRoomView` 0x0202A5D8, `DSystem.CallEnterGameTrigger` 0x021E5C8C,
 * `SkinTrialManager.CreateNewEquipItem` 0x02AC00E0, …) — i.e. every time battle preparation
 * opens **and** on entering a game — and it silently overwrites any equipped slot whose gun id
 * is absent from `inventory`.
 *
 * `tbnz w0,#0,+0x28` → **`b +0x28`** (`0A000014`) makes the branch unconditional, i.e. always
 * take the "keep the slot" path. `nop` would be wrong here: it would fall straight *into* the
 * repair block. Re-decoded, the forced branch target `0x021D290C + 0x28 = 0x021D2934` is
 * byte-identical to the original `tbnz` target, so the loop converges on exactly the same code
 * a genuinely-owned gun takes — the counter `sub w22,w22,#1` and the loop back-edge at
 * `0x021D2938` are reached identically, and the routine's remaining work (armour handling, and
 * the trailing `equipedItems[0]=2 / [1]=1` fill at `0x021D2950`) is untouched. The only
 * behaviour removed is the "replace an un-owned equipped gun with gun 1 or 2" rewrite, which is
 * the bug. Nothing else in the function is written.
 *
 * Side effect to be aware of, not a hazard: the routine also contains a "refill the `-1`
 * empty-slot sentinel" pass at `0x021D2F44…0x021D2FD4`, inside `GetCurEquipedGunBattle`. Site 7
 * does not touch it, and it still runs.
 *
 * ---- SITE 8 — `DSystem.GetOwnedGunConfigById(int gunid)` — `nop` the null early-out (4 B) ----
 *  VA 0x021D2DB0 · file 0x021CEDB0 · write 4 B · anchor 36 B
 *
 * ```
 *  021d2dac: bl   0x36c6420       ; inventory.ContainsKey(gunid)
 *  021d2db0: tbz  w0, #0x0, 0x21d2e88   ; NOT owned -> return null   <-- site 8
 *  021d2db4: adrp x8, ...         ; happy path: DUtil.GetLevelItem(gunid)
 *  021d2dd4: bl   0x28dde68        ;   -> GetConfigByIdLevel(id, level)
 *  021d2e8c: mov  x0, xzr ; 021d2e94: ret   ; the null path that is skipped
 * ```
 *
 * `tbz w0,#0,+0x28` → **`nop`** (`1F2003D5`): execution falls through to the happy path,
 * which is the instruction immediately after the branch — so `nop` is exactly right (as opposed
 * to site 7, whose fall-through is the *bad* path). The `UpgradeWeaponRecord` the gun is built
 * from is now produced even for an un-owned id.
 *
 * ---- SITE 9 — `DSystem.GetCurEquipedGunBattle(int indexEquip)` — `nop` the null early-out ----
 *  VA 0x021D30E4 · file 0x021CF0E4 · write 4 B · anchor 36 B
 *
 * Same shape on the battle-preparation path, and this is the one that crashes: the record
 * returned by sites 8/9 is dereferenced without a null check —
 *
 * ```
 *  020ff4fc: ldr  x0, [x24]
 *  020ff500: bl   0x32f5954       ; DSystem.GetCurEquipedGun(indexEquip)
 *  020ff504: cbz  x0, 0x2102ce4   ; -> il2cpp_null_reference_throw  (NullReferenceException)
 * ```
 *
 * i.e. `GunController.Init` treats `null` as fatal, not as "fall back", so without sites 8/9 a
 * freshly equipped un-owned gun degrades into a thrown `NullReferenceException` during gun
 * construction. Both sites are reached from the live path: `DSystem.GetCurEquipedGun(int)`
 * tail-`b`s to `GetCurEquipedGunBattle` at `0x021D1D28` and to `GetOwnedGunConfigById` at
 * `0x021D1E3C` (site 8 has **0** BL callers precisely because it is only ever tail-called).
 *
 * **What makes sites 8/9 safe** (this is the load-bearing asymmetry, re-decoded here): the level
 * lookup they both call already tolerates an absent id. `DUtil.GetLevelItem(int)` @ `0x028DDE68`
 * does `inventory.ContainsKey(id)` and, on the not-owned path, returns a **static default level**
 * read from `DSystem`'s static-field slot (`0x028DDF48 ldr x8,[x0,#0xb8]` →
 * `0x028DDF50 ldr w0,[x8,#0x20]`) — it does not throw and does not return `null`. So forcing
 * sites 8/9 past their early-outs yields a *well-formed* `UpgradeWeaponRecord` at the default
 * level, with no other change needed.
 *
 * ---- SITE 10 — `RaidbossSelectionView.CheckItemLocked(int itemId)` — `mov w0,#0 ; ret` ----
 *  VA 0x0206B9A8 · file 0x020679A8 · write 8 B (entry) · anchor 36 B
 *
 * Genuine entry (same `stp x30,x21` word 0 as sites 1/3/6; `mov w19,w1` = itemId at +0x18,
 * `mov x20,x0` = this at +0x1C). Body ends `mvn w8,w0 ; and w0,w8,#1`, i.e. it returns
 * **`!inventory.ContainsKey(itemId)`** — so the constant here is **false**, not true:
 * `00008052 C0035FD6` = `mov w0,#0 ; ret`. That is the only site in this file whose stub
 * returns zero; the direction is easy to get backwards, so it is called out here.
 *
 * 3 BL callers, all in the raid-boss mode item list (`0x0206B0BC`, `0x0206B8C4`, `0x0206BB04`).
 * **Scope-limited**: it affects raid-boss mode only, and is the one gate here that is *mode*-scoped
 * rather than global. Included because the goal is "guns usable", it is the same predicate over
 * the same collection as the other four, and it is verified unique and disjoint. If raid-boss
 * mode is out of scope, this site can be dropped without affecting the other nine.
 *
 * ============================================================================
 * REPLACEMENT STUBS
 * ============================================================================
 * Entry stubs (sites 1–3, 6) — `mov w0,#1 ; ret` = `20 00 80 52 | C0 03 5F D6` (8 bytes):
 *  - `mov w0,#1` → `20 00 80 52` (0x52800020). The bool return value comes back in **w0**
 *    (AArch64 ABI, 32-bit return), so this is the correct register.
 *  - `ret`        → `C0 03 5F D6` (0xD65F03C0).
 *
 * Inverted entry stub (site 10) — `mov w0,#0 ; ret` = `00 00 80 52 | C0 03 5F D6` (8 bytes):
 *  - `mov w0,#0` → `00 00 80 52` (0x52800000). `CheckItemLocked` returns *locked*, so the
 *    forcing constant is `false`. Occurrence count over the whole library: **0** — the IL2CPP
 *    compiler never emits this pair, so unlike the `mov w0,#1 ; ret` stub it is not
 *    "self-corroborating". That is fine (uniqueness is required of the *original* anchor, which
 *    site 10 has), but it is why this stub's encoding was cross-checked with `llvm-mc` instead
 *    of being read off the library.
 *
* Inline stubs (sites 4–5) — `mov w0,#1` = `20 00 80 52` (4 bytes) only. **No `ret`**: the
 * enclosing `Refresh` must keep running so it loads `_iconOwned` and calls `SetActive`.
 *
 * Branch-forcing edits (sites 7–9) — 4 bytes each, again **no `ret`** (same reasoning: these
 * are mid-function):
 *  - site 7 `b #0x28` → `0A 00 00 14` (0x1400000A): always take the "keep the slot" branch.
 *  - sites 8/9 `nop` → `1F 20 03 D5` (0xD503201F): always fall through to the happy path.
 *
 * Encodings cross-validated against the library itself rather than hand-assembled, counted
 * over the full 85,048,024 bytes:
 *
 *  | byte string | meaning | occurrences | used by |
 *  |---|---|---|---|
 *  | `20008052C0035FD6` | `mov w0,#1 ; ret` | **1,735** | sites 1–3, 6 |
 *  | `20008052`         | `mov w0,#1`       | **9,104** | sites 4–5 |
 *  | `C0035FD6`         | `ret` alone       | 196,005 | — |
 *  | `00008052C0035FD6` | `mov w0,#0 ; ret` | 0       | site 10 |
 *  | `0A000014`         | `b #+0x28`        | 4,297   | site 7 |
 *  | `1F2003D5`         | `nop`             | 3,664   | sites 8–9 |
 *
 * All six rows were re-counted in this pass (whole-file byte search). The 1,735 figure matches
 * the number cited in ../unlock/UnlimitedCurrencyPatch.kt and ../ads/InstantRewardedVideoPatch.kt,
 * which independently cross-validates the 8-byte stub as one the IL2CPP compiler itself emits.
 * The 4-byte `mov w0,#1` is likewise the compiler's own constant-true form — which is why site 5's
 * neighbourhood already contains one.
 *
 * ⚠️ The three branch-forcing encodings were additionally checked the other way round — against
 * the assembler rather than against the library — with
 * `llvm-mc --triple=aarch64-linux-android -show-encoding`, because site 7 replaces a `tbnz` and
 * sites 8–9 replace `tbz`, and getting the polarity backwards would invert the intended fix:
 *
 * ```
 *  tbnz w0, #0, #40   => [0x40,0x01,0x00,0x37]   == site 7 ORIGINAL first 4 B  ✓
 *  b     #40          => [0x0a,0x00,0x00,0x14]   == site 7 REPLACEMENT          ✓
 *  tbz   w0, #0, #40  => [0x40,0x01,0x00,0x36]   == sites 8/9 ORIGINAL pattern   ✓
 *  nop                => [0x1f,0x20,0x03,0xd5]   == sites 8/9 REPLACEMENT        ✓
 *  mov  w0, #0        => [0x00,0x00,0x80,0x52]   == site 10 REPLACEMENT         ✓
 * ```
 *
 * (Note the `tbnz`/`tbz` distinction: site 7's original really is `tbnz` — 0x…37, not 0x…36 —
 * and the two encode to byte sequences differing only in the last byte. That is precisely why
 * site 7's 36-byte window, not its 4-byte prefix, is what identifies the instruction.)
 *
 * Frequency figures for the stub *patterns* are also in
 * `analysis/dead-target/notes/cosmetic-unlock-targets.md` §3.E; the replacement obviously
 * cannot be uniquely anchored (it is a common sequence) and does not need to be — uniqueness
 * is required of the **original anchor only**.
 *
 * Overwriting a prologue at sites 1–3, 6, 10 is safe: the stub returns immediately and **never
 * touches the stack**, so the discarded `stp x21,x20,[sp,#-32]!` / `stp x20,x19,[sp,#16]`
 * frame is simply never executed — no unbalanced sp, no callee-saved register clobber, no
 * leak. The `x1`/`w1` argument is ignored, which is exactly the point of a constant `true`
 * (site 10 a constant `false`). Sites 4–5 and 7–9 discard no prologue at all; see SITES 4 AND 5
 * and SITES 6 TO 10.
 *
 * ============================================================================
 * ANCHOR UNIQUENESS — all ten anchors, 36 bytes, exactly one hit each
 * ============================================================================
 * Occurrence counts for progressively longer prefixes of each anchor, counted over the whole
 * 85 MB library. "min unique" is the shortest prefix that occurs exactly once.
 *
 *  | Site | 8 B | 12 B | 16 B | 20 B | 24 B | 28 B | 32 B | 36 B | min unique |
 *  |------|------|------|------|------|------|------|------|------|------------|
 *  | 1 IsGunUnlock  | 24,734 | 3 | 1 | 1 | 1 | 1 | 1 | 1 | 16 B |
 *  | 2 IsOwnGlove   | 17,473 | 17,473 | **2** | **2** | 1 | 1 | 1 | 1 | **24 B** |
 *  | 3 CheckHaveGun | 24,734 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 12 B |
 *  | 4 drone inline | **1** | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 8 B |
 *  | 5 glove inline | **1** | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 8 B |
 *  | 6 IsOwnedItem  | 24,734 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 12 B |
 *  | 7 FixedWrongEq | **1** | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 8 B |
 *  | 8 GetOwnedGun  | **1** | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 8 B |
 *  | 9 GetCurEquiBd | **1** | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 8 B |
 *  |10 CheckItemLock| 24,734 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 12 B |
 *
 * Every 36-byte window is exactly **1 occurrence**, and in all ten cases that single hit is
 * the documented file offset (i.e. `hit == VA − 0x4000`) — re-verified against the shipped
 * library for all ten rows above, in this pass, with three independent counting methods
 * (Python `find`-loop, `bytes.count`, and `xxd -p` piped to GNU `grep -o`) which agree exactly.
 *
 *  - Site 1 needs 16 bytes; 36 is used anyway, matching the sibling patches and giving margin
 *    against a future build shifting surrounding code.
 *  - Site 2 **must not** use a 16- or 20-byte anchor: both are ambiguous (2 hits — the other
 *    is `DSystem.GetSkinIndex(string,string)`). 24 bytes is the minimum; 36 is used.
 *  - Sites 4, 5, 7, 8 and 9 are unique even at 4 bytes (their instruction encodings each occur
 *    once in 85 MB), but that is irrelevant: **all 36 bytes are still searched and verified**,
 *    because these windows start at a non-entry point and a 4-byte match would prove nothing
 *    about *which* instruction it was. The 36-byte window pins the collection load, the id load
 *    and the consumer. Only the first 4 are ever written.
 *  - Sites 6 and 10 share sites 1/3's word 0 (`stp x30,x21,[sp,#-0x20]!`, 24,734 hits at 8 B),
 *    so they need 12 bytes, exactly as sites 1 and 3 do. Site 6's discriminator is the
 *    `mov w19,w1`/`mov x20,x0` pair at +0x10/+0x14 followed by `adrp`-into-the-shared-generic
 *    body; site 10's is the same pair at +0x18/+0x1C followed by the `mvn`/`and` inversion tail.
 *
 * ⚠️ **Discrepancy found while re-verifying site 9, recorded so it is not re-litigated:**
 * `analysis/dead-target/notes/gun-usable-gate.md` §5 (G4) states that site 9's 8-byte prefix
 * occurs **12** times and that 12–28 bytes are ambiguous, with **32 bytes** the minimum unique
 * length. That row is **not reproducible**: all three counting methods above find **exactly one**
 * occurrence at *every* length from 8 B up, and that single hit is at the documented offset
 * `0x021CF0E4`. The note's conclusion (use the long window) is therefore kept — 36 bytes is
 * used for site 9 like every other site — but its *reason* was wrong, and the ambiguity it
 * warns about does not exist. Recorded rather than silently "fixed", because if a future build
 * really does make that prefix ambiguous the guard machinery below would reject it loudly rather
 * than write into the wrong place.
 *
 * Only the first 8 bytes (sites 1–3, 6, 10) or first 4 bytes (sites 4–5, 7–9) are ever written,
 * and each
 * write is same-length, so the remaining 32 / 32 anchor bytes are pure verification. Every
 * write is additionally guarded by read-original-bytes-then-compare (see [applyItemAnchors]) and
 * by the "exactly one occurrence" check, so a new game build fails loudly with a
 * [PatchException] instead of writing into the wrong function.
 *
* ============================================================================
* NO OVERLAP WITH THE OTHER DEAD TARGET PATCHES
* ============================================================================
* **24 write windows** are live across the Dead Target patch set:
*  - 5 × 12-byte currency getters — ../unlock/UnlimitedCurrencyPatch.kt: `0x218BD78`,
*    `0x2192464`, `0x21925DC`, `0x2193234`, `0x2194A2C`
*  - 1 × 12-byte ads window — ../ads/InstantRewardedVideoPatch.kt: `0x283BDE0`
*  - 8 × 4-byte god-mode windows — ./cheat/GodModeCheatMenuPatch.kt: `0x2142988`, `0x214299C`,
*    `0x21429B4`, `0x21F4AA4`, `0x21F4FFC`, `0x21F52F0`, `0x22B4FB0`, `0x22B4FC8`
*  - this file's ten (8+8+8+4+4+8+4+4+4+8 = 60 bytes across 10 windows)
*
* The interval-overlap test `aStart < bEnd && bStart < aEnd` was re-run over **all
* C(24,2) = 276** pairs, on **both** axes — once with the *write* lengths (4/8/12 B) and once
* with the full *anchor* lengths (36 B, or the write length where there is no anchor) — because
* two patches can be disjoint where they write yet still collide where they *verify*. Both runs
* return **zero overlaps: all 24 windows pairwise disjoint**, twice.
*
* Nearest neighbour per window added in this pass (the five gun-usability gates):
*
*  | new window | write | nearest window | byte distance |
*  |---|---|---|---|
*  | 6 `0x020A4708` | 8 B | site 10 `0x020679A8` | 249,176 B (243.3 KB) |
*  | 7 `0x021CE90C` | 4 B | site 8 `0x021CEDB0` | **1,184 B (1.2 KB)** |
*  | 8 `0x021CEDB0` | 4 B | site 9 `0x021CF0E4` | **816 B (0.8 KB)** |
*  | 9 `0x021CF0E4` | 4 B | site 8 `0x021CEDB0` | **816 B (0.8 KB)** |
*  |10 `0x020679A8` | 8 B | site 6 `0x020A4708` | 249,176 B (243.3 KB) |
*
* The tightest separations are sites 8↔9 at **816 B** (~200× the 4-byte write) — these three
* gates live in three adjacent `DSystem` methods (`FixedWrongEquipWeapon`, `GetOwnedGunConfigById`,
* `GetCurEquipedGunBattle` are consecutive methods at `0x021D26C0`, `0x021D2D38`, `0x021D2E9C`),
* which is inherent to where the gates are, not a hazard: 816 B is ~17× the 36-byte anchor and
* ~200× the write, so no anchor search can reach into a neighbouring window. Their nearest
* *pre-existing* neighbour is site 3 (`CheckHaveGun`, `0x021D1BB4`) at 10,956 B.
* For reference, the tightest separation anywhere in the 24-window set is **20 B**, and it is
* between two god-mode writes (`0x02142988` / `0x0214299C`) — pre-existing, disjoint, and not
* anything this patch introduced.
*
* (Historic note: when this file had 5 sites, the tightest pair was 22.2 KB, site 3 ↔ site 1.
* The five new gates are all *closer* to each other than that, which is why the 816 B row above
* is quoted rather than the old 22.2 KB figure.)
 *
 * ============================================================================
 * BATTLE-PASS CONSEQUENCE — site 3 lands on the other leg of site 1's AND
 * ============================================================================
 * `ConfigLazyEvent4BattlePassRecord.GetBattlePassRewardId(int rewardId, bool isFreePass,
 * out bool isCompensation)` at VA `0x023C43D4` gates gun-skin battle-pass rewards on
 * `SkinData.IsEnable(skinIndex) && DSystem.IsGunUnlock(gunId)`:
 *
 * ```
 *   23c45e0: bl    0x24b1a44               <- SkinData.IsEnable(skinIndex)
 *   23c45e4: tbz   w0, #0x0, 0x23c46f8     <- if NOT owned -> keep original rewardId
 *   23c45fc: bl    0x21db470               <- DSystem.IsGunUnlock(gunId)   <-- site 1 (shipped)
 *   23c4600: tbz   w0, #0x0, 0x23c46f8     <- if NOT gun-unlocked -> keep original rewardId
 * ```
 *
 * (Both branch targets re-decoded from the library bytes and confirmed.) Because `IsEnable`
 * reduces to `CheckHaveGun(gunId)` — literally the same predicate as `IsGunUnlock` at a
 * different entry — **both legs of the AND are the same predicate.** Site 1 already forced
 * the second leg; **site 3 completes the first.** So this is *not* a new class of side effect:
 * it is the already-documented site-1 behaviour arriving through the skin leg instead of the
 * gun leg.
 *
 * Quantified increment: battle-pass gun-skin rewards whose `IsEnable` currently returns false
 * now take the "owned/claimed" branch. `IsEnable` already returns `true` when the gun config
 * cannot be resolved (`0x024B1D60: mov w0,#1`), so the affected set is exactly the rewards
 * whose gun is present in config **and** absent from `inventory`.
 *
 * What the "owned" branch does: it tests `List<int>.Contains` over `UserData.claimFreePass`
 * (0x600) or `UserData.claimBattlePass` (0x608), selected by `isFreePass`; if the reward has
 * already been claimed it **appends the skin picName** to `UserData.skinClaimInEvent`
 * (`List<string>.Add` at VA `0x023C46F0`) and then returns **reward id 23 with
 * `isCompensation = true`** instead of the real reward — verified at `0x023C4714`:
 * `mov w8,#1 ; mov w0,#0x17 (23) ; strb w8,[x19]`. The visible effect is that some
 * battle-pass gun-skin reward slots may show as compensation/claimed rather than offering the
 * skin. Nothing crashes and no currency is granted by this patch. The one write on this path
 * is a `List<string>.Add` of a string the caller already holds, so it cannot shift another
 * list's indices and cannot throw. Called out plainly rather than hidden.
 *
* `UnlockSkinAfterBuyPack` (VA `0x0204F534`) and `UserData.IsBattlePassNewPackAvailable`
* (TypeDefIndex 3770) call none of these ten sites and are unaffected.
*
* ============================================================================
* REMOVAL / CONSUMPTION GUARDS — none of the ten sites has one
* ============================================================================
*  | Site | Any caller that removes or consumes? | Verdict |
*  |---|---|---|
*  | 1 `IsGunUnlock` | 1 of 5 (`RemoveGun`, guarded `Dictionary.Remove` on an absent key = no-op) | safe |
*  | 2 `IsOwnGlove(string)` | No. 12 callers, all reads/filters. | safe |
*  | 3 `CheckHaveGun` | No. 32 callers, all reads/filters. | safe |
*  | 4 drone inline `ContainsKey` | No. Single consumer is `_iconOwned.SetActive`. | safe |
*  | 5 glove inline `List.Contains` | No. Single consumer is `_iconOwned.SetActive`. | safe |
*  | 6 `WeaponShopView.IsOwnedItem` | No. 9 callers, all button/layout wiring + price display. | safe |
*  | 7 `FixedWrongEquipWeapon` branch | **Supplies** the write this patch skips (`equipedItems[slot]` = fallback id); removing that branch removes a write, adds none. | safe |
*  | 8 `GetOwnedGunConfigById` branch | No. Pure return-value producer; sole caller is `GetCurEquipedGun`. | safe |
*  | 9 `GetCurEquipedGunBattle` branch | No. Pure return-value producer. | safe |
*  |10 `CheckItemLocked` | No. 3 callers, raid-boss lock icons/taps only. | safe |
*
* Every patched value is consumed either by `SetActive(bool)` or by a boolean branch, and the
* underlying collections are never written, so no patched site can shift a collection index or
* throw. Site 7 is the one case where the patched instruction used to *lead to* a write — and
* that write is precisely the bug being fixed. This is also why the *other* candidates below
* were rejected: they DO guard destructive operations.
*
* **Side effect of site 6 worth stating:** with the shop's only ownership predicate forced
* true, the shop stops quoting *purchase* prices for un-owned guns and starts quoting *upgrade*
* prices (`GetCurPrice` / `GetCurTypePrice` are two of its nine callers), and `_lbGunLocked`
* plus the buy/upgrade button row resolve to the owned layout. That is self-consistent with
* "you own it" and matches the effect site 1 already had on sale/offer surfaces, but it is a
* visible change in the shop and is called out rather than hidden.
 *
 * ============================================================================
 * VERIFIED DEAD ENDS AND REJECTED TARGETS — do not retry any of these
 * ============================================================================
* **`DSystem.IsGunUnlockV2(ItemConfig)` — DEAD END.** VA `0x021DBD4C` / file `0x021D7D4C`.
* The shipped game already contains the stub: the bytes at that offset are
* `20 00 80 52 C0 03 5F D6`, byte-identical to the replacement this patch writes. The devs
* shipped it as a hardcoded `return true`. Writing the same 8 bytes is a **pure no-op**.
* Recorded so nobody retries it. Its 36-byte anchor
* (`20008052C0035FD6FFC301D1FE5F04A9F65705A9F44F06A9757A01B0A83A5039F403012A`) is still
* unique, so if a future build ever puts a real body there, re-evaluate — not before.
* (Confirmed again in the gun-usability pass: it is a display-only `always true` like site 1 and
* adds nothing on the equip path either.)
*
* **`DSystem.get_IsMeleeModeUnlock()` — DEAD END, another dev-shipped stub.** VA `0x0218F754` /
* file `0x0218B754`. Its body is `mov w0,wzr ; ret` — a hardcoded **`return false`**, shipped by
* the devs, exactly like `IsGunUnlockV2` above. So melee mode is *off* in this build, which means
* "the gun is gated out of melee mode" **cannot** be the equip bug, and there is nothing to patch
* here: writing the same two instructions back is a no-op. Recorded so nobody spends a pass on a
* melee-mode gun gate.
*
* **`DSystem.EnableGod` (0xB23) / `DSystem.EnablePhoenix` (0xB22) — DEAD ENDS, and not god mode.**
* These are `DSystem` cheat *flags*, and two independent findings kill them here:
*  1. **Not god mode.** All 9 read sites are `UpgradeWeaponRecord` skill-rate getters
*     (`GodRate` → `1.0f`, `GodDelayMin` → `9.0f`, `PhoenixRate`, `PhoenixDamageRate`,
*     `PhoenixTime`, `PhoenixDelay`) — an *offence-bonus cheat for the God/Hades/Zeus gun skins*,
*     additionally gated on `isUseCheat`. There is no "player takes no damage" consumer
*     anywhere, despite the name.
*  2. **Zero writers.** Nothing in the shipped build ever stores `true` into either field (an
*     exhaustive `str`/`strb` scan finds none), so even setting them would only work until
*     something else clears them — and nothing does, which is why the flag cannot self-reset.
* Both facts are established in `analysis/dead-target/notes/godmode-cheat-targets.md` §3 and
* repeated in ./cheat/GodModeCheatMenuPatch.kt. Re-confirmed in this pass: the field is read as
* `ldrb w8,[x0,#0xb23]` / `#0xb22` and the corresponding `cbnz` targets are the rate getters.
* Not touched by this patch.
*
* **`SkinData.isOwnSkin(int skinidx)` — DEAD CODE.** VA `0x024B279C` / file `0x024AE79C`. The
* bytes are a perfect little leaf predicate (`PartInfo.ContainsKey`), and the exhaustive
* BL-target scan of the whole executable LOAD segment finds **0 call sites**. Patching it
* would change nothing. (Re-confirmed in this pass: the scan used for the gun-usability work
* still reports 0 callers for this target.)
 *
 * **`DSystem.IsOwnSkin(int skinId)` — REJECTED: real bytes, WRONG gate.** VA `0x021DB2D4` /
 * file `0x021D72D4`. Genuine prologue, unique 36-byte anchor — and only **3** BL callers, all
 * legacy-collection code: `LegacyCollectionManager.ClaimUnclaimedRewards()` (branch VA
 * `0x023CEFA4`), `LegacyCollectionManager.ClaimReward(...)` (`0x023D01BC`) and
 * `PopupLegacyCollection.InitItems(List<int>)`. **Zero** shop or inventory callers. It is also
 * a *dispatcher*, not a leaf: it reads a type word at `+0x14`, compares against `0x64`/`0xC8`/
 * `0x12C` (100/200/300), tail-branches to `IsOwnGlove(int)` for 300 and to
 * `isOwnedGunSkin(int,int)` for 100/200, and returns `false` otherwise — so it does not even
 * cover every skin type. Patching it would achieve nothing in the skin shop while corrupting
 * two reward-claim guards.
 *
 * **`DSystem.IsOwnedDrone()` — REJECTED: WRONG semantics.** VA `0x021DA490` / file
 * `0x021D6490`. Verified real, but the dump.cs signature is `public bool IsOwnedDrone() { }`
 * — **no parameter**. Its body is `droneInventory.Count > 0`, i.e. "do you own *any* drone",
 * so forcing it true unlocks **zero** individual drones. Worse, one of its only two callers is
 * `DroneManager.DropItem()` — a loot-drop function — so it would make the drone drop-item
 * generator believe a drone is always owned: a gameplay/economy change, not a cosmetic unlock.
 * Site 4 is the correct drone site.
 *
 * **`DSystem.IsOwnGlove(int gloveid)` — REJECTED.** VA `0x021DB268` / file `0x021D7268`.
 * Only **2** callers, both inside `ConfigFrankExclusiveReward.GetRewardByGun(string)`
 * (branch VAs `0x023C3A3C`, `0x023C3CF4`) — a Frank-event helper, not the glove shop.
 * `IsOwnGlove(string)` (site 2) is the one the shop uses.
 *
 * **`isOwnedGunSkin(string,int)` / `isOwnedGunSkin(int,int)` — REJECTED.** VA `0x021D5C14` /
 * `0x021C7668`. The first is the guard inside `DSystem.DeleteSkin(string,int)` (branch VA
 * `0x021D94FC`) and `DSystem.GetGunTokenSkin(bool)` (`0x021C6E38`), i.e. it authorises
 * **deleting** skins and **consuming** a gun-token skin the player does not hold. The second
 * has only 6 callers, all legacy-collection / medal code, no shop.
 *
 * **`SkinData.IsEnable(int skinIndex)` — REJECTED as too broad.** VA `0x024B1A44`. It is the
 * correct predicate (site 3's proof chain), but **53** callers including
 * `UserData.ToThisByBinaryData` (save deserialisation), the gacha reward pickers,
 * `DialogLoginReward.BtnOKClick`, `HalloweenEvent.*` and `MiniEventBoxController.IsDupData`.
 * Site 3 reaches the same result through a 32-caller, read-only, DSystem-level entry point.
 *
 * **`get_IsDroneUnlocked()`** (VA `0x0218F7A4`) and **`LazyEventController.IsOwnedItem(int)`**
 * (VA `0x0239F95C`) — verified real and unique, but the first is a *feature* gate ("is the
 * drone feature unlocked at all", 2 callers in `ControlMapView.CheckActiveTutorialInWorldMap`),
 * the second an *event-reward* filter. Neither unlocks individual items. Excluded.
 *
 * **`ConfigLazyEvent4BattlePassRecord.IsGunSkinAndUserOwned(int,bool)`** (VA `0x023C4970`) —
 * verified real but its minimum unique prefix is **24 bytes**, not 12, and forcing it true is
 * a battle-pass *change* rather than a cosmetic unlock. Excluded.
 *
* **The shared generic collection helpers** `Dictionary<int,int>.ContainsKey` @ `0x036C6420`,
* `List<int>.Contains` @ `0x03BE9A04`, `Dictionary<string,SkinData>.ContainsKey` @ `0x03757558`
* — hundreds of callers each across the whole engine (config tables, `FileBrowser`, `TreeView`,
* `TMP_FontUtilities`, …). Never patch these. Sites 4 and 5 exist precisely *because* they
* must not be, and sites 6–10 for the same reason: five of them are the *call sites* of this very
* shared generic (`WeaponShopView.IsOwnedItem` tail-branches to `0x36C6420`, and so do the
* `ContainsKey` calls inside `FixedWrongEquipWeapon`, `GetOwnedGunConfigById` and
* `GetCurEquipedGunBattle`). Patching the callee would rewrite the engine; patching the
* *narrower* caller is what sites 6–10 do.
*
* **`DSystem.EquipedItems(int,int)` / `EquipGun(UpgradeWeaponRecord)` / `SwitchItemsEquiped()` /
* `CheckEquipIfNeed(int)` — REJECTED: NO GATE.** The whole equip *commit* chain was disassembled
* and contains **zero** ownership reads: `WeaponShopView.ChangeEquipment` → `EquipedItems` /
* `SwitchItemsEquiped` → `EquipGun` writes `equipedItems[slot] = gunId` unconditionally. This is
* the good news half of the gun-usability finding — you *can* commit an equip for any id — and it
* is also why the fix is sites 7–9 (stop the repair, stop the null record) rather than a patch on
* the commit itself.
*
* **`DSystem.HaveEquipGun(int)` — REJECTED: wrong data.** VA `0x021D61C4` / file `0x021D21C4`.
* Reads `UserData.equipedItems` (0x110), i.e. "is equipped", not "is owned".
* `DSystem.OnwedGunValueThan(int)`, `IsGunHavePromoteSystem(int)`,
* `UnlockPromoteSystemByGunOwned()`, `IfChallengeModeAvailableAllGun/ByEquipGun` — rejected:
* gun *value* comparison and challenge-mode power flags, not ownership.
* `BattlePreparationView.HaveEnoughPowerForBattle` (VA `0x02B98044`) — rejected: a power check
* that calls `DUtil.GetLevelItem`, which is safe for un-owned ids (see site 8/9 above).
*
* ============================================================================
* HONEST SCOPE — this is a READ patch, and here is exactly what that means
* ============================================================================
* All ten sites make ownership **reads** — or the branches that hang off them — answer
* "owned". That is the whole mechanism, and it has a precise limit worth stating rather than
* glossing — stated once here for the merged patch:
*
*  - **Nothing is added to any owned list.** No gun id is inserted into `UserData.inventory`
 *    (0xE8) or `gunList`, no glove id into `UserData.listHandSkin` (0x488), no drone id into
 *    `UserData.droneInventory` (0xF0), no skin part into `SkinData.PartInfo`.
 *  - **Therefore nothing is written to the save file / persistent profile.** Every item is
 *    unlocked *as far as the game's own code can observe it* — shop tiles, selectors,
 *    reward filters — while the underlying save data is untouched. A real purchase still
 *    persists normally; this patch neither duplicates nor suppresses that.
 *  - **Sites 4 and 5 change routing, not just icons.** `DroneItemCtrl.OnClick` has no
 *    ownership guard, so a drone tile that now renders as owned routes to the equip/select
 *    flow rather than the buy flow. Since the drone is genuinely absent from
 *    `droneInventory`, whatever consumes the equip request may find nothing to equip. UI
 *    state only — no id is added — but a real behavioural difference from the unpatched
 *    build, and not glossed over.
*  - **`SkinData.PartInfo` (individual skin parts / upgrade pieces) is untouched.** Gun-skin
*    ownership reduces to gun ownership, so tiles light up, but `AddPart` / `SetPart` /
*    `GetPart` progression is unaffected.
*  - **Drone *skins* ride along only indirectly.** Site 4 covers the `DroneItemCtrl` path;
*    other drone-skin surfaces were not enumerated exhaustively and may not be covered.
*  - **Nothing is written to the save file, and nothing persists.** Sites 6–10 make a gun
*    *usable in this session* — the shop wires the Equip/Upgrade button, the loadout repair
*    stops overwriting your slots, and the battle-prep record resolvers stop returning `null`.
*    But `inventory` still lacks the id, so every predicate this patch does not touch keeps
*    saying "not owned" (sale banners, offer suppression, `GunTrainManager` trial lists, quest /
*    medal `IsMatchCondition`, `ProgressRoadHelper`, `RecommendPackMng`, `EventGunTrial`,
*    `TreasureHuntingEventController.GetCurrentGunTier`, `TopupEventController`,
*    `BattlePass` reward choice, `ControlMap2D` / `ControlMapView` sale notices,
*    `MissionInfoView` recommend/buy), and a reinstall or save wipe resets everything.
*    **Only the devs' own cheat methods (below) actually persist an unlock.**
*  - **Guns: the fix is now complete for the reported bug, but it is still not device-tested.**
*    The whole five-gate chain is static analysis of the shipped 4.183.0 library; expect the
*    first device run to reveal at least one more `inventory` reader. If guns still will not
*    equip after this, the search is NOT back at site 1 — it is at an `inventory` reader this
*    note did not enumerate.
*  - **Skins, gloves and drones are structurally different, and all three remain untested.**
*    Only guns have both a *repair pass* (`FixedWrongEquipWeapon`, site 7) and a *null-returning
*    record resolver* (sites 8/9), which is why guns uniquely failed. The static evidence:
*      - **drones — structurally safest.** The commit `DSystem.EquipedDrone(int)` is a raw field
*        write (`userData.equippedDrone = id`, VA `0x021D5E38`, no ownership read) and
*        `DroneManager.SpawnDrone` reads that field directly (`0x02255310 ldr w1,[x8,#0x118]`)
*        with no `droneInventory` check. No repair pass, no null resolver.
*      - **skins — probably fine.** The apply path (`GunController.FindGloveAndSkin` →
*        `GunSkinManager.GetCurSkinIndex`, VA `0x0288A04C`) queries `UserData+0x540`
*        (`gunSkinData`) and never consults `inventory`; there is no `FixedWrongEquipWeapon`
*        analogue and no null-record resolver for skins.
*      - **gloves — weakest of the three.** No `UpgradeWeaponRecord`-style resolver and no
*        repair pass was found, but glove application in battle
*        (`ControlGloveSkin.RefreshButtons` VA `0x0240E20C`, `GlovesView.RefreshButtons`
*        `0x02BC0024`) was **not** disassembled far enough to prove it never re-consults
*        `listHandSkin` (0x488). Flagged unverified.
*    ⚠️ "No second gate found" is weaker evidence than "a second gate found and fixed" — the gun
*    case is the proof of that. The user has device-tested **guns only**; nothing here claims
*    skins, gloves or drones are fixed on hardware.
*  - **Not covered, and not claimed to be:** `UnlockSkinAfterBuyPack`,
*    `UserData.IsBattlePassNewPackAvailable`, and the battle-pass *reward selection* surfaces
*    are affected only as described in BATTLE-PASS CONSEQUENCE, not unlocked.
 *
 * If you want the ids genuinely *written* into the owned lists, see the upgrade path below.
 *
 * ============================================================================
 * UPGRADE PATH — the devs' own cheat methods (real state writes, NOT implemented here)
 * ============================================================================
 * `DSystem` ships its own cheat routines, and these are the ones that make **real state
 * changes** — they append the ids to the owned lists, so the unlock *persists to the
 * save file*:
 *
 *    DSystem.UnlockAllGuns()       VA 0x021D9730   (private; just a loop calling UnlockItem)
 *    DSystem.UnlockAllGunSkins()   VA 0x021DA614
 *    DSystem.UnlockAllGloves()     VA 0x021DA988
 *    DSystem.UnlockDrone(int,bool) VA 0x021DA3C8
 *    DSystem.UnlockGloveById(int)  VA 0x021DABCC
 *    DSystem.UnlockGunSkinOnly     VA 0x021D9F50
 *    DSystem.UnlockDroneSkinOnly   VA 0x021DA1B0
 *    DSystem.UnlockItem(int)       VA 0x021D9414   (the per-gun write the loop calls)
 *    DSystem.UnlockGunByIAPOK(int,string) VA 0x021D9080  (the devs' own IAP-bypass unlock)
 *    DSystem.AutoUnlockInventory() VA 0x0219E84C   (private)
 *
* These are all ordinary managed methods with no bytecode body you can flatten to a constant
* — they are *runtime invocations*. Making them fire needs an `il2cpp_class_from_name` +
* `il2cpp_runtime_invoke` call from a **runtime companion ARM64 `.so`** loaded after the
* Unity/Il2Cpp domain is up (the ubisoftpop route: `JNI_OnLoad` → poll
* `il2cpp_domain_get` → `il2cpp_thread_attach` → resolve → invoke). That is the real
* unlock-with-persistence, and it is **deliberately not implemented here** — it needs an NDK
* toolchain and a second bytecode half to trigger `System.loadLibrary`, versus the
* same-length static byte edits used above.
*
* Worth being explicit about the relationship between the two routes: **the cheat methods are
* the only way to make an unlock persist**, and invoking them would make sites 6–10 largely
* redundant (a real state write fixes every `inventory` reader at once, including the ~98 direct
* readers). They are kept separate here because the reported bug — guns owned but unusable — is
* fully fixable with static bytes, and the `.so` route is ~10× the work for an equivalent result
* *for this symptom*. If a future goal is persistence rather than usability, do the `.so`.
*
* Trade-off, stated plainly: this patch is a static edit that needs no NDK and survives the
* whole VNG re-sign flow, at the cost of not writing the save file. The dev cheats persist,
* at the cost of a companion `.so`. Choose deliberately, not by accident.
*
* ============================================================================
* SCOPE — deliberately narrow: ownership READS and the branches on them, nothing else
* ============================================================================
* * No `AdsCallbacks` / `MediationManager` / `AdsController` method — ads live in
*   ../ads/InstantRewardedVideoPatch.kt.
* * No currency getter — currency lives in ../unlock/UnlimitedCurrencyPatch.kt.
* * No god-mode field (`DSystem.EnableGod`, `0xB23`), no `EnablePhoenix` (`0xB22`) and no
*   `showcheat` UI flag — and note those two flags are not god mode anyway and have no writer;
*   see VERIFIED DEAD ENDS.
* * No `armeabi-v7a` branch: this XAPK ships **arm64-v8a ONLY** (splits: base +
*   `UnityDataAssetPack.apk` + `config.arm64_v8a.apk`), so a second anchor table would be
*   dead code.
* * Every rejected candidate in VERIFIED DEAD ENDS AND REJECTED TARGETS stays rejected.
 *
 * ============================================================================
 * DELIVERY — how the patched .so lands in the XAPK split pipeline
 * ============================================================================
 * Same shape as both sibling Dead Target patches, which shipped and device-verified on this
 * same XAPK (see their DELIVERY sections, and Dead Trigger's NativeIapVerifierBypass KDoc
 * for the full Javap read-writeup of the classes involved): `PatchEngine` runs
 * `ApkMerger.merge()` FIRST, so base + `UnityDataAssetPack.apk` + `config.arm64_v8a.apk`
 * become ONE merged APK before any patch executes; the config split is what carries
 * `lib/arm64-v8a/libil2cpp.so`. A `rawResourcePatch` anywhere in the patch set forces
 * `ResourceMode.RAW_ONLY`, so `get(path, true)` resolves the raw-extracted
* `lib/<abi>/libil2cpp.so`. **All ten writes are SAME-LENGTH in place** — 8-over-8 at sites
* 1–3, 6 and 10, 4-over-4 at sites 4–5 and 7–9 — so `detectFileChanges()` catches each on
* `lastModified` → `ApkUtils.applyTo` overlays them into the rebuilt APK →
* `signWithLegacyFallback`. Only the first 8 (resp. 4) bytes of each 36-byte anchor are written;
* the other 28/32 are verified and left alone, so the file length never changes and no `lib/`
* entry is re-added.
 *
 * Static file patch (chosen) vs runtime companion `.so`: the `.text` is plaintext on disk,
 * there is NO `.so` integrity / signature / anti-tamper check anywhere in the chain
 * (protection-bypass.md: no pairip, no Java signature verification, and VNG
 * `libpglarmor.so` is not even Java-loaded), and the whole bundle is re-signed as one unit —
 * so a static byte edit needs no NDK, no companion `.so`, no `System.loadLibrary` trigger
 * and no `mprotect` dance. It also sidesteps the ubisoftpop packed-lib timing lesson
 * outright: nothing is touched at runtime, before or after Unity's native init.
 *
 * Discovery: a top-level `val … = rawResourcePatch(…)` IS a public static field of type
 * Patch, which is exactly what `PatchLoader` scans (see the DISCOVERY notes in
 * ../../../deadtrigger/patches/il2cpp/NativeIapVerifierBypass.kt's KDoc). One concern →
 * plain top-level `val`, **no** `dependsOn`. list-patches therefore shows exactly one
 * "All items owned" entry per Dead Target patch set.
 */
@Suppress("unused")
val allItemsOwnedPatch = rawResourcePatch(
    name = "All items owned",
    description = "Every gun, skin, glove and drone shows as unlocked. Pick and equip anything you like.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEAD_TARGET)

    execute {
        // arm64-v8a only — this XAPK has no armeabi-v7a split.
        applyItemAnchors(get("lib/arm64-v8a/libil2cpp.so", true), ARM64_ITEM_ANCHORS)
    }
}

/**
 * One guarded byte-range replacement in `libil2cpp.so`.
 *
 * [anchorHex] is the ORIGINAL 36-byte window (must occur exactly ONCE in the library — that
 * uniqueness is what makes the match self-verifying); [replacementHex] is the replacement
 * written over the first bytes of that window — **8 bytes** at the five function-entry sites,
 * **4 bytes** at the five mid-function sites. The two lengths are independent axes on purpose:
 * five of the ten sites carry a 36-byte verification window with only 4 bytes written.
 *
 * Named `ItemAnchor`, not `Anchor`, and that is the ONLY deviation from the sibling patches'
 * structure: this file shares package `app.deadtarget.patches.unlock` with
 * ../unlock/UnlimitedCurrencyPatch.kt, which already declares a private top-level `Anchor`.
 * Kotlin reports a hard `Redeclaration` for a second same-named private top-level *class* in
 * one package, and then makes the first file's own references inaccessible. The sibling file
 * is not modified by this patch, so the collision is resolved on this side instead: the shape
 * (same five fields, same meaning, same two-phase application) is identical, only the
 * identifier differs. Same reasoning renames [ARM64_ITEM_ANCHORS] and [applyItemAnchors], and
 * is why the stub constants below are named after what they *do* (`BOOL_TRUE`, `BOOL_TRUE_W`,
 * `BOOL_FALSE`, `BRANCH_KEEP_SLOT`, `NOP_FALLTHROUGH`) rather than reusing the sibling's
 * `STUB_INT64` / `STUB_INT32`.
 *
 * The `Item` prefix is the natural label for the merged 10-site scope (guns, gun skins, gloves,
 * drones, glove tile + the five gun-usability gates) *and* the thing that keeps this file off the
 * sibling's names, so the two requirements point the same way. Note the asymmetry that makes
 * this legal: the collision rule applies to the top-level **class** only — the file-private
 * top-level **functions** `hex`, `indexOfAll` and `toHex` are legitimately duplicated between
 * this file and the sibling and are deliberately kept byte-identical. Do not "tidy" those
 * duplicates into a shared helper; only the class name is load-bearing here.
 */
private class ItemAnchor(
    /** Human label used in logs and [PatchException] messages. */
    val label: String,
    /** VIRTUAL address from Il2CppDumper's script.json (kept for messages only). */
    val va: Long,
    /** Documented exact file offset = va − 0x4000 (exec LOAD delta, see KDoc). */
    val fileOffset: Long,
    /** Hex of the ORIGINAL 36-byte window — must occur exactly once, or the patch aborts. */
    val anchorHex: String,
    /** Hex of the replacement written at that window's first bytes (8 B entry, 4 B inline). */
    val replacementHex: String,
)

/**
 * `mov w0,#1 ; ret` — for the four **function-entry** sites that answer "owned"
 * (sites 1, 2, 3, 6): the predicate returns true for every id. Cross-validated: this exact
 * 8-byte stub occurs 1,735 times in the shipped library.
 */
private const val BOOL_TRUE = "20008052C0035FD6"

/**
 * `mov w0,#1` alone, **no `ret`** — for the two **mid-body** sites, where the enclosing
 * `Refresh` must continue running so it can load `_iconOwned` and call `SetActive`. This
 * replaces a single 4-byte `bl` word, so the write is 4-over-4.
 */
private const val BOOL_TRUE_W = "20008052"

/**
 * `mov w0,#0 ; ret` — site 10 only, the one gate whose predicate is *inverted*:
 * `RaidbossSelectionView.CheckItemLocked` returns `!inventory.ContainsKey(id)`, so "never
 * locked" is a constant **false**. Occurs 0 times in the library (the compiler never emits this
 * pair), so unlike [BOOL_TRUE] it is not self-corroborating — its encoding comes from
 * `llvm-mc --show-encoding`, and its correctness comes from the body's `mvn`/`and` tail.
 */
private const val BOOL_FALSE = "00008052C0035FD6"

/**
 * `b #+0x28` — site 7 only. Replaces the `tbnz w0,#0,+0x28` skip-the-repair branch in
 * `DSystem.FixedWrongEquipWeapon` with an unconditional one, so an equipped slot is kept even
 * when the gun id is absent from `inventory`. `nop` would be wrong: it falls into the repair
 * block. Encoded form verified with `llvm-mc` (`b #40` → `0a 00 00 14`) and against the
 * library's own 4,297 occurrences.
 */
private const val BRANCH_KEEP_SLOT = "0A000014"

/**
 * `nop` — sites 8 and 9. Replaces the `tbz w0,#0,+0x28` "not owned → `return null`" early-out
 * in the two gun-record resolvers, so control falls through to the happy path already sitting at
 * the next instruction. 4-over-4, no `ret`: the enclosing method must run to completion. Encoded
 * form verified with `llvm-mc` (`nop` → `1f 20 03 d5`) and against the library's own 3,664
 * occurrences.
 */
private const val NOP_FALLTHROUGH = "1F2003D5"

/**
 * The ten sites, in the order documented in the patch KDoc: guns tile, gloves, gun skins,
 * drone tile, glove tile (display), then the five gun-usability gates (shop button, loadout
 * repair, record resolvers, raid-boss lock). Every entry's [ItemAnchor.anchorHex] is 36 bytes
 * and occurs exactly once in the shipped `libil2cpp.so`; the single hit is the documented
 * `fileOffset` in all ten cases, re-verified in this pass.
 *
 * Note the deliberate independence of the two axes: the anchor is always 36 bytes, while the
 * write is 8 bytes at the five entry sites and 4 bytes at the five mid-function sites.
 */
private val ARM64_ITEM_ANCHORS = listOf(
    // ---- Site 1: GUN TILE ICON. Function entry: 0xA9BE57FE `stp x21,x20,[sp,#-32]!`, and +0x10
    // is `mov x19,x1` = idGun. Display only — see ROOT CAUSE in the patch KDoc; the equip/use
    // gates are sites 6–10. Unchanged from the originally shipped patch.
    ItemAnchor(
        label = "DSystem.IsGunUnlock -> true (gun tile icon: inventory.ContainsKey tail-call)",
        va = 0x021DB470,
        fileOffset = 0x021D7470,
        // Word 0 = 0xA9BE57FE `stp x21,x20,[sp,#-32]!` (genuine entry); +0x10 holds
        // `mov x19,x1` = idGun. Only the first 8 bytes are ever written.
        anchorHex =
            "FE57BEA9F44F01A9757A01B0" +
            "A82E5039F303012AF40300AA" +
            "C8000037E05E01F000AC40F9",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 2: GLOVES (primary). Function entry: 0xF81D0FFE `str x30,[sp,#-0x30]!`, and
    // entry+0x1C holds `mov x20,x1` = glovename. 16- and 20-byte windows are AMBIGUOUS
    // (2 hits, the other being DSystem.GetSkinIndex), so 36 bytes is required, not padding.
    ItemAnchor(
        label = "DSystem.IsOwnGlove(string) -> true (gloves: hand-skin picName check)",
        va = 0x021C9314,
        fileOffset = 0x021C5314,
        // Word 0 = 0xF81D0FFE `str x30,[sp,#-0x30]!` (genuine entry); +0x1C holds
        // `mov x20,x1` = glovename. Only the first 8 bytes are ever written.
        anchorHex =
            "FE0F1DF8F65701A9F44F02A9" +
            "F67A01F0955F0190C81A5039" +
            "B57A45F9F40301AAF30300AA",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 3: SKINS. Function entry: 0xA9BE57FE `stp x30,x21,[sp,#-0x20]!`, and entry+0x10
    // holds `mov w19,w1` = idGunRecommend. Body tail-branches to
    // Dictionary<int,int>.ContainsKey @ 0x036C6420, the same generic IsGunUnlock uses — i.e.
    // in this game gun-skin ownership IS gun ownership, at a second entry point.
    ItemAnchor(
        label = "DSystem.CheckHaveGun -> true (gun skins: ownership is gunList.ContainsKey)",
        va = 0x021D5BB4,
        fileOffset = 0x021D1BB4,
        // Word 0 = 0xA9BE57FE `stp x30,x21,[sp,#-0x20]!` (genuine entry); +0x10 holds
        // `mov w19,w1` = idGunRecommend. Only the first 8 bytes are ever written.
        anchorHex =
            "FE57BEA9F44F01A9957A01F0" +
            "A84E5039F303012AF40300AA" +
            "C8000037205F01B000AC40F9",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 4: DRONES. *** MID-FUNCTION INLINE EDIT — NOT AN ENTRY *** The anchor starts at
    // a `bl` inside DroneItemCtrl.Refresh, NOT at the method's first word, so no prologue
    // argument applies. Word 0 = 0x9442437C = `bl 0x036C6420` = Dictionary<int,int>.ContainsKey;
    // `ldr x0,[x9,#0xF0]` at -0x18 pins the collection to UserData.droneInventory and
    // `ldr w1,[x8,#0x10]` at -0x08 puts DroneConfig.id in w1. 4-byte write, NO `ret`: the
    // method must continue to `ldr x8,[x19,#0x40]` / `_iconOwned.SetActive`. All 36 bytes are
    // still verified even though only the first 4 are written.
    ItemAnchor(
        label = "DroneItemCtrl.Refresh inline droneInventory.ContainsKey -> true (drone tile)",
        va = 0x02635630,
        fileOffset = 0x02631630,
        anchorHex =
            "7C434294682240F9080800B4" +
            "01000012E00308AAE2031FAA" +
            "73F18A94601E40F9400700B4",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 5: GLOVES tile icon. *** MID-FUNCTION INLINE EDIT — NOT AN ENTRY *** Starts at a
    // `bl` inside GloveItemCtrl.Refresh. Word 0 = 0x945671F7 = `bl 0x03BE9A04` =
    // List<int>.Contains; `ldr x0,[x8,#0x488]` at -0x18 pins the collection to
    // UserData.listHandSkin and `ldr w1,[x19,#0x70]` at -0x08 puts this._gloveId in w1. Needed
    // on top of site 2 because this tile inlines the test instead of calling IsOwnGlove. Note
    // the `20008052` at +0x08 is the game's OWN gloveId==0 "always owned" shortcut at
    // 0x0264D230, not something this patch writes. 4-byte write, NO `ret`.
    ItemAnchor(
        label = "GloveItemCtrl.Refresh inline listHandSkin.Contains -> true (glove tile icon)",
        va = 0x0264D228,
        fileOffset = 0x02649228,
        anchorHex =
            "F77156940200001420008052" +
            "682640F9680300B401000012" +
            "E00308AAE2031FAA73928A94",
        replacementHex = BOOL_TRUE_W,
    ),
    // ---- Site 6: GUNS SHOP ACTION BUTTON. Function entry: 0xA9BE57FE `stp x30,x21,[sp,#-0x20]!`
    // (shared with sites 1/3/10 — 24,734 hits at 8 B), and +0x10 holds `mov w19,w1` = idItem,
    // +0x14 `mov x20,x0` = this. Body: `ldr x0,[x20,#0x1E8]` (this._data.Inventory, filled from
    // UserData.inventory by UIState.UpdateWeaponShopData) -> tail-`b 0x036C6420` =
    // Dictionary<int,int>.ContainsKey. The shop's ONLY ownership predicate (9 BL callers);
    // SetupBtnForNormalWeapon branches on it at 0x020AA0CC to pick the Equip/Upgrade wiring
    // over the Unlock/Buy wiring. 8-byte entry write.
    ItemAnchor(
        label = "WeaponShopView.IsOwnedItem -> true (gun shop button: Equip/Upgrade not Buy)",
        va = 0x020A8708,
        fileOffset = 0x020A4708,
        anchorHex =
            "FE57BEA9F44F01A9F58301F0" +
            "A8826E39F303012AF40300AA" +
            "C8000037806801D000AC40F9",
        replacementHex = BOOL_TRUE,
    ),
    // ---- Site 7: GUNS LOADOUT REPAIR. *** MID-FUNCTION BRANCH EDIT — NOT AN ENTRY *** Word 0 =
    // 0x37000140 `tbnz w0,#0,+0x28` — "owned, keep the equipped slot" — inside
    // DSystem.FixedWrongEquipWeapon. Replaced with an unconditional `b +0x28` (same target,
    // 0x021D2934), so the slot is never overwritten with the fallback gun id. Runs on EVERY
    // SetupEquipment() (11 BL callers) — this is the gate that actually ate the equip.
    // 4-byte write, NO `ret`: the loop must continue and the routine must finish its other work.
    ItemAnchor(
        label = "DSystem.FixedWrongEquipWeapon -> always keep the equipped gun slot",
        va = 0x021D290C,
        fileOffset = 0x021CE90C,
        anchorHex =
            "4001003768D240F9880900B4" +
            "008940F9200900B4630340F9" +
            "DF02007142179A1AE103142A",
        replacementHex = BRANCH_KEEP_SLOT,
    ),
    // ---- Site 8: GUN RECORD RESOLVER. *** MID-FUNCTION BRANCH EDIT *** Word 0 = 0x360006C0
    // `tbz w0,#0,+0x28` — "not in inventory -> return null" — inside
    // DSystem.GetOwnedGunConfigById. Replaced with `nop` so control falls through to the happy
    // path already at the next instruction (GetLevelItem -> GetConfigByIdLevel). Safe because
    // DUtil.GetLevelItem already returns a static default level for an un-owned id instead of
    // throwing or returning null. 4-byte write, NO `ret`.
    ItemAnchor(
        label = "DSystem.GetOwnedGunConfigById -> do not return null for an un-owned gun",
        va = 0x021D2DB0,
        fileOffset = 0x021CEDB0,
        anchorHex =
            "C0060036285F01D008E946F9" +
            "000140F908E040B948000035" +
            "0209F397E003132AE1031FAA",
        replacementHex = NOP_FALLTHROUGH,
    ),
    // ---- Site 9: GUN BATTLE-PREP RECORD RESOLVER. *** MID-FUNCTION BRANCH EDIT *** Same shape on
    // the battle-prep path: 0x360006A0 `tbz w0,#0,+0x28` -> `nop`. This is the one that crashes:
    // GunController.Init does `cbz x0, <null throw>` on the result (0x020FF504), so without
    // sites 8+9 an un-owned equipped gun produces a NullReferenceException during construction.
    // Anchor is 36 B even though the note reported a 32 B minimum — see ANCHOR UNIQUENESS.
    ItemAnchor(
        label = "DSystem.GetCurEquipedGunBattle -> do not return null for an un-owned gun",
        va = 0x021D30E4,
        fileOffset = 0x021CF0E4,
        anchorHex =
            "A0060036285F01B008E946F9" +
            "931240B9000140F908E040B9" +
            "480000353408F397E003132A",
        replacementHex = NOP_FALLTHROUGH,
    ),
    // ---- Site 10: RAID-BOSS MODE ITEM LOCK. Function entry: 0xA9BE57FE `stp x30,x21,[sp,#-0x20]!`
    // (24,734 hits at 8 B), +0x18 `mov w19,w1` = itemId, +0x1C `mov x20,x0` = this. Body ends
    // `mvn w8,w0 ; and w0,w8,#1`, i.e. it returns !inventory.ContainsKey(id) — so the constant
    // is FALSE here, not true. 3 BL callers, all in the raid-boss mode item list; the only
    // mode-scoped gate in this file. 8-byte entry write.
    ItemAnchor(
        label = "RaidbossSelectionView.CheckItemLocked -> false (raid-boss items never locked)",
        va = 0x0206B9A8,
        fileOffset = 0x020679A8,
        anchorHex =
            "FE57BEA9F44F01A9F4850190" +
            "756A01B088466539B55646F9" +
            "F303012A28010037606A01F0",
        replacementHex = BOOL_FALSE,
    ),
)

/**
 * Applies every [ItemAnchor] to [lib], guarded by a unique-anchor search.
 *
 * Two-phase so a bad build fails with **zero** bytes written:
 *  1. the library is slurped once and every 36-byte anchor is searched for, requiring
 *     **exactly one** occurrence (an 8-byte search would hit 24,734 times and a 12-byte one
 *     still 3 times at the guns site — and site 2 is ambiguous even at 16 bytes, so a shorter
 *     window would corrupt an unrelated function). This applies uniformly to the five
 *     mid-function sites too: although their 4-byte instruction encodings are themselves unique
 *     in 85 MB, the full 36 bytes are searched and verified, because the window is what pins the
 *     collection load, the id load and the consumer, and therefore identifies *which*
 *     instruction it is;
 *  2. each resolved offset is re-read through a [RandomAccessFile], compared against the
 *     original bytes, and only then overwritten.
 *
 * Every write is the same length as the bytes it replaces — 8-over-8 at the five entry sites,
 * 4-over-4 at the five mid-function sites — so the patcher's `lastModified`-keyed change diff
 * picks it up. Throws [PatchException] with full context if an anchor is missing or ambiguous,
 * or if the bytes on disk are not what we expect — i.e. new game build, unsupported version.
 * Because phase 1 completes for *all ten* anchors before phase 2 opens the file for writing,
 * a single bad anchor anywhere means zero bytes are modified.
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
                    "Unsupported app version?",
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
            // Not fatal — the unique anchor is authoritative — but the .so moved, so say so
            // loudly instead of silently shipping.
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
                    "(file 0x${at.toString(16)}), ${replacement.size}-byte stub: " +
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
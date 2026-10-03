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
 * lives in `libil2cpp.so` (85,048,024 bytes, arm64-v8a, plaintext `.text`). This patch turns
 * the game's own ownership *predicates* into constants, so **everything unlockable reads as
 * owned**: every gun is selectable and equippable, every gun-skin tile is ticked, every glove
 * lights up in the shop and in the in-battle selector, and every drone tile renders as owned.
 *
 * **Five sites, five same-length in-place edits** in one file — one guns site, two gloves
 * sites, one gun-skin site and one drone site (originally the guns site alone, with the other
 * four cosmetic sites merged in from
 * `analysis/dead-target/notes/cosmetic-unlock-targets.md`). Same shape as
 * ../unlock/UnlimitedCurrencyPatch.kt and ../ads/InstantRewardedVideoPatch.kt.
 *
 * ============================================================================
 * THE FIVE SITES
 * ============================================================================
 *  | # | What                | Predicate / site                        | VA        | File offset | Write | Kind          |
 *  |---|---------------------|-----------------------------------------|-----------|-------------|-------|---------------|
 *  | 1 | GUNS                | `DSystem.IsGunUnlock(int)`              | 0x021DB470| 0x021D7470  | 8 B   | entry stub    |
 *  | 2 | GLOVES (primary)    | `DSystem.IsOwnGlove(string)`            | 0x021C9314| 0x021C5314  | 8 B   | entry stub    |
 *  | 3 | SKINS               | `DSystem.CheckHaveGun(int)`             | 0x021D5BB4| 0x021D1BB4  | 8 B   | entry stub    |
 *  | 4 | DRONES              | inline `droneInventory.ContainsKey`     | 0x02635630| 0x02631630  | 4 B   | **mid-body**  |
 *  | 5 | GLOVES tile icon    | inline `listHandSkin.Contains`          | 0x0264D228| 0x02649228  | 4 B   | **mid-body**  |
 *
 * ⚠️ Sites 1–3 overwrite a **function entry**, so the stub is `mov w0,#1 ; ret`. Sites 4–5
 * are **mid-function inline edits**: they replace a single 4-byte `bl` instruction and must
 * NOT be given a `ret`. See SITES 4 AND 5 for the three substitute proofs that replace the
 * entry-prologue test there.
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
 *
 * Every write is **same-length in place** — 8-over-8 at sites 1–3, 4-over-4 at sites 4–5 — which
 * is what the patcher's `lastModified`-keyed change diff detects (see DELIVERY).
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
 * delta 0x0 / +0x8000 / +0xC000, so it must never be applied to `.data` / `.bss`. All five
 * sites here live in the `il2cpp` section (file `0x1F78B28 … 0x4B6790C`), which carries the
 * same `VA − file = +0x4000` — re-checked per site against the shipped library, all five
 * agree (VA − file = 0x4000 for every one).
 *
 * ============================================================================
 * SITE 1 — `DProject.DSystem.IsGunUnlock(int idGun)` (GUNS) — entry stub
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
 * `IsGunUnlock` is not a flag read: its body *is* the ownership test. It walks
 * `UserData.gunList` and tail-calls `List<int>.Contains(idGun)` — visible in the shipped
 * bytes as `ldr x8,[this+0x1A0]` (the UserData holder field, same `#0x1A0` offset the
 * currency getters in ../unlock/UnlimitedCurrencyPatch.kt read) → `ldr x0,[x8,#0xE8]` (the
 * backing int array) → a tail-`b` into the list `Contains` helper. The last words of the
 * window are exactly that shape: `+0x50 0xF9400102` `ldr x0,[x2,#4]`,
 * `+0x54 0xA8C257FE` `ldp x30,x21,[sp],#32` (frame torn down first), then
 * `+0x58 0x1453ABD6` `b <Contains>` — a **tail** branch, so the function's own return value
 * is `gunList.Contains(idGun)`.
 *
 * Forcing that to `true` therefore makes *every* gun id read as owned, everywhere, because
 * every consumer funnels through this one predicate rather than reading `gunList` itself.
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
 * Per-caller consequence:
 *  - **`WeaponItemCtrl::Init` / `::Refresh` are the point of the patch.** The boolean feeds
 *    an `and w1,w21,#1` and then a call — it is the per-slot "is this gun owned" flag that
*    drives the inventory tile's locked/unlocked state. Forcing it true is what makes every
*    gun selectable and equippable.
 *  - `DSystem::CheckRestoreAllGunHasSkin` — a save-repair routine; it will now consider every
 *    gun owned. Harmless (see `RemoveGun` below).
 *  - `DSystem::RemoveGun` — used as a `tbz w0,#0` guard before `gunList.Remove(idGun)`.
 *    Forcing the predicate true lets the removal proceed for ids that are not in the list.
 *    **Verified safe:** `List<int>.Remove` on an absent id is a documented no-op (no
 *    exception, no index shift), so this cannot corrupt `gunList` or the save file. It is
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
 * REPLACEMENT STUBS
 * ============================================================================
 * Entry stubs (sites 1–3) — `mov w0,#1 ; ret` = `20 00 80 52 | C0 03 5F D6` (8 bytes):
 *  - `mov w0,#1` → `20 00 80 52` (0x52800020). The bool return value comes back in **w0**
 *    (AArch64 ABI, 32-bit return), so this is the correct register.
 *  - `ret`        → `C0 03 5F D6` (0xD65F03C0).
 *
 * Inline stubs (sites 4–5) — `mov w0,#1` = `20 00 80 52` (4 bytes) only. **No `ret`**: the
 * enclosing `Refresh` must keep running so it loads `_iconOwned` and calls `SetActive`.
 *
 * Encodings cross-validated against the library itself rather than hand-assembled, counted
 * over the full 85,048,024 bytes:
 *
 *  | byte string | meaning | occurrences |
 *  |---|---|---|
 *  | `20008052C0035FD6` | `mov w0,#1 ; ret` (sites 1–3) | **1,735** |
 *  | `20008052`         | `mov w0,#1` (sites 4–5)      | **9,104** |
 *  | `C0035FD6`         | `ret` alone                  | 196,005 |
 *  | `00008052C0035FD6` | `mov w0,#0 ; ret`            | 0       |
 *
 * The 1,735 figure matches the number cited in ../unlock/UnlimitedCurrencyPatch.kt and
 * ../ads/InstantRewardedVideoPatch.kt, which independently cross-validates the 8-byte stub as
 * one the IL2CPP compiler itself emits. The 4-byte `mov w0,#1` is likewise the compiler's own
 * constant-true form — which is why site 5's neighbourhood already contains one. Frequency
 * figures for the stub *patterns* are in
 * `analysis/dead-target/notes/cosmetic-unlock-targets.md` §3.E; the replacement obviously
 * cannot be uniquely anchored (it is a common sequence) and does not need to be — uniqueness
 * is required of the **original anchor only**.
 *
 * Overwriting a prologue at sites 1–3 is safe: the stub returns immediately and **never
 * touches the stack**, so the discarded `stp x21,x20,[sp,#-32]!` / `stp x20,x19,[sp,#16]`
 * frame is simply never executed — no unbalanced sp, no callee-saved register clobber, no
 * leak. The `x1`/`w1` argument is ignored, which is exactly the point of a constant `true`.
 * Sites 4–5 discard no prologue at all; see SITES 4 AND 5.
 *
 * ============================================================================
 * ANCHOR UNIQUENESS — all five anchors, 36 bytes, exactly one hit each
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
 *
 * Every 36-byte window is exactly **1 occurrence**, and in all five cases that single hit is
 * the documented file offset (i.e. `hit == VA − 0x4000`) — re-verified against the shipped
 * library for all five rows above.
 *
 *  - Site 1 needs 16 bytes; 36 is used anyway, matching the sibling patches and giving margin
 *    against a future build shifting surrounding code.
 *  - Site 2 **must not** use a 16- or 20-byte anchor: both are ambiguous (2 hits — the other
 *    is `DSystem.GetSkinIndex(string,string)`). 24 bytes is the minimum; 36 is used.
 *  - Sites 4 and 5 are unique even at 4 bytes (their `bl` encodings each occur once in 85 MB),
 *    but that is irrelevant: **all 36 bytes are still searched and verified**, because the
 *    window starts at a non-entry point and a 4-byte match would prove nothing about
 *    *which* `Contains` call it was. The 36-byte window pins the `ldr` of the collection, the
 *    `ldr` of the id, and the `_iconOwned` load. Only the first 4 are ever written.
 *
 * Only the first 8 bytes (sites 1–3) or first 4 bytes (sites 4–5) are ever written, and each
 * write is same-length, so the remaining 32 / 32 anchor bytes are pure verification. Every
 * write is additionally guarded by read-original-bytes-then-compare (see [applyItemAnchors]) and
 * by the "exactly one occurrence" check, so a new game build fails loudly with a
 * [PatchException] instead of writing into the wrong function.
 *
 * ============================================================================
 * NO OVERLAP WITH THE OTHER DEAD TARGET PATCHES
 * ============================================================================
 * Eleven write windows are live across the Dead Target patch set: five 12-byte currency
 * getters (../unlock/UnlimitedCurrencyPatch.kt — `0x218BD78`, `0x21925DC`, `0x2193234`,
 * `0x2192464`, `0x2194A2C`), one 12-byte ads window (`../ads/InstantRewardedVideoPatch.kt` —
 * `0x283BDE0`), and this file's five (8+8+8+4+4 bytes). The interval-overlap test
 * (`aStart < bEnd && bStart < aEnd`) over all **C(11,2) = 55** pairs returns
 * **all 11 windows pairwise disjoint: True**, zero overlaps — re-run here, not taken on trust.
 *
 * Nearest existing site per new window:
 *
 *  | new window | nearest existing site | byte distance |
 *  |---|---|---|
 *  | 2 `0x021C5314` | this file's `IsGunUnlock` `0x021D7470` | 74,068 B (72.3 KB) |
 *  | 3 `0x021D1BB4` | this file's `IsGunUnlock` `0x021D7470` | **22,708 B (22.2 KB)** |
 *  | 4 `0x02631630` | `InstantRewardedVideo` `0x0283BDE0` | 2,140,076 B (2.04 MB) |
 *  | 5 `0x02649228` | `InstantRewardedVideo` `0x0283BDE0` | 2,042,804 B (1.95 MB) |
 *
 * The tightest separation anywhere is **22.2 KB** (site 3 ↔ site 1) — ~2,800× the 8-byte write
 * length, so no patched region can bleed into a neighbouring patch's anchor search. Sites 3
 * and 1 are also *semantically* near-identical predicates, which is intentional (see SITE 3),
 * not a hazard.
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
 * (TypeDefIndex 3770) call none of these five sites and are unaffected.
 *
 * ============================================================================
 * REMOVAL / CONSUMPTION GUARDS — none of the five sites has one
 * ============================================================================
 *  | Site | Any caller that removes or consumes? | Verdict |
 *  |---|---|---|
 *  | 1 `IsGunUnlock` | 1 of 5 (`RemoveGun`, guarded `List<int>.Remove` on an absent id = no-op) | safe |
 *  | 2 `IsOwnGlove(string)` | No. 12 callers, all reads/filters. | safe |
 *  | 3 `CheckHaveGun` | No. 32 callers, all reads/filters. | safe |
 *  | 4 drone inline `ContainsKey` | No. Single consumer is `_iconOwned.SetActive`. | safe |
 *  | 5 glove inline `List.Contains` | No. Single consumer is `_iconOwned.SetActive`. | safe |
 *
 * Every patched value is consumed either by `SetActive(bool)` or by a boolean branch, and the
 * underlying collections are never written, so no patched site can shift a collection index or
 * throw. This is exactly why the *other* candidates below were rejected: they DO guard
 * destructive operations.
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
 *
 * **`SkinData.isOwnSkin(int skinidx)` — DEAD CODE.** VA `0x024B279C` / file `0x024AE79C`. The
 * bytes are a perfect little leaf predicate (`PartInfo.ContainsKey`), and the exhaustive
 * BL-target scan of the whole executable LOAD segment finds **0 call sites**. Patching it
 * would change nothing.
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
 * must not be.
 *
 * ============================================================================
 * HONEST SCOPE — this is a READ patch, and here is exactly what that means
 * ============================================================================
 * All five sites make ownership **reads** return true. That is the whole mechanism, and it
 * has a precise limit worth stating rather than glossing — stated once here for the merged
 * patch:
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
 * Trade-off, stated plainly: this patch is a static edit that needs no NDK and survives the
 * whole VNG re-sign flow, at the cost of not writing the save file. The dev cheats persist,
 * at the cost of a companion `.so`. Choose deliberately, not by accident.
 *
 * ============================================================================
 * SCOPE — deliberately narrow: ownership READS, nothing else
 * ============================================================================
 * * No `AdsCallbacks` / `MediationManager` / `AdsController` method — ads live in
 *   ../ads/InstantRewardedVideoPatch.kt.
 * * No currency getter — currency lives in ../unlock/UnlimitedCurrencyPatch.kt.
 * * No god-mode field (`DSystem.EnableGod`, `0xB23`) and no `showcheat` UI flag.
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
 * `lib/<abi>/libil2cpp.so`. **All five writes are SAME-LENGTH in place** — 8-over-8 at sites
 * 1–3, 4-over-4 at sites 4–5 — so `detectFileChanges()` catches each on `lastModified` →
 * `ApkUtils.applyTo` overlays them into the rebuilt APK → `signWithLegacyFallback`. Only the
 * first 8 (resp. 4) bytes of each 36-byte anchor are written; the other 28/32 are verified
 * and left alone, so the file length never changes and no `lib/` entry is re-added.
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
 * written over the first bytes of that window — **8 bytes** at the three function-entry sites,
 * **4 bytes** at the two mid-body `bl` sites.
 *
 * Named `ItemAnchor`, not `Anchor`, and that is the ONLY deviation from the sibling patches'
 * structure: this file shares package `app.deadtarget.patches.unlock` with
 * ../unlock/UnlimitedCurrencyPatch.kt, which already declares a private top-level `Anchor`.
 * Kotlin reports a hard `Redeclaration` for a second same-named private top-level *class* in
 * one package, and then makes the first file's own references inaccessible. The sibling file
 * is not modified by this patch, so the collision is resolved on this side instead: the shape
 * (same five fields, same meaning, same two-phase application) is identical, only the
 * identifier differs. Same reasoning renames [ARM64_ITEM_ANCHORS] and [applyItemAnchors].
 *
 * The `Item` prefix is the natural label for the merged 5-site scope (guns, gun skins,
 * gloves, drones, glove tile) *and* the thing that keeps this file off the sibling's names, so
 * the two requirements point the same way. Note the asymmetry that makes this legal: the
 * collision rule applies to the top-level **class** only — the file-private top-level
 * **functions** `hex`, `indexOfAll` and `toHex` are legitimately duplicated between this file
 * and the sibling and are deliberately kept byte-identical, as are `BOOL_TRUE` / `BOOL_TRUE_W`
 * against the sibling's `STUB_INT64` / `STUB_INT32` (same slots, different name). Do not
 * "tidy" those duplicates into a shared helper; only the class name is load-bearing here.
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
 * `mov w0,#1 ; ret` — for the three **function-entry** sites: the ownership predicate returns
 * true for every id. Cross-validated: this exact 8-byte stub occurs 1,735 times in the shipped
 * library.
 */
private const val BOOL_TRUE = "20008052C0035FD6"

/**
 * `mov w0,#1` alone, **no `ret`** — for the two **mid-body** sites, where the enclosing
 * `Refresh` must continue running so it can load `_iconOwned` and call `SetActive`. This
 * replaces a single 4-byte `bl` word, so the write is 4-over-4.
 */
private const val BOOL_TRUE_W = "20008052"

/**
 * The five sites, in the order documented in the patch KDoc: guns, gloves, skins, drones,
 * glove tile. Every entry's [ItemAnchor.anchorHex] is 36 bytes and occurs exactly once in the
 * shipped `libil2cpp.so`; the single hit is the documented `fileOffset` in all five cases.
 */
private val ARM64_ITEM_ANCHORS = listOf(
    // ---- Site 1: GUNS. Function entry: 0xA9BE57FE `stp x21,x20,[sp,#-32]!`, and +0x10 is
    // `mov x19,x1` = idGun. Unchanged from the originally shipped patch.
    ItemAnchor(
        label = "DSystem.IsGunUnlock -> true (gun ownership check: gunList.Contains tail-call)",
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
)

/**
 * Applies every [ItemAnchor] to [lib], guarded by a unique-anchor search.
 *
 * Two-phase so a bad build fails with **zero** bytes written:
 *  1. the library is slurped once and every 36-byte anchor is searched for, requiring
 *     **exactly one** occurrence (an 8-byte search would hit 24,734 times and a 12-byte one
 *     still 3 times at the guns site — and site 2 is ambiguous even at 16 bytes, so a shorter
 *     window would corrupt an unrelated function). This applies uniformly to the two
 *     mid-body sites too: although their 4-byte `bl` encodings are themselves unique in 85 MB,
 *     the full 36 bytes are searched and verified, because the window is what pins the
 *     collection load and the id load and therefore identifies *which* `Contains` call it is;
 *  2. each resolved offset is re-read through a [RandomAccessFile], compared against the
 *     original bytes, and only then overwritten.
 *
 * Every write is the same length as the bytes it replaces — 8-over-8 at the three entry sites,
 * 4-over-4 at the two inline sites — so the patcher's `lastModified`-keyed change diff picks
 * it up. Throws [PatchException] with full context if an anchor is missing or ambiguous, or
 * if the bytes on disk are not what we expect — i.e. new game build, unsupported version.
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
                "Dead Target all items owned: ${anchor.label} — 36-byte anchor not found in " +
                    "${lib.name} (size=${bytes.size}). Expected file offset 0x" +
                    "${anchor.fileOffset.toString(16)} (VA 0x${anchor.va.toString(16)}). " +
                    "Unsupported app version?",
            )

            hits.size > 1 -> throw PatchException(
                "Dead Target all items owned: ${anchor.label} — 36-byte anchor is AMBIGUOUS " +
                    "(${hits.size} occurrences: " +
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
package app.headsoccer.patches.collab

import app.headsoccer.patches.shared.Constants.COMPATIBILITY_HEAD_SOCCER
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly

/**
 * Head Soccer 7.1.12 · **Unlock Collab Characters** — the characters that normally require
 * another D&D Dream game to be installed are unlocked with a single tap, with no Play Store
 * round-trip and nothing installed.
 *
 * cocos2d-x (`com.dnddream.headsoccer.android`, versionCode 358). **DEX-only — `libgame.so` is
 * left byte-identical.** The whole feature lives in three static probes in the app's own
 * `headsoccer` class (classes.dex).
 *
 * ============================================================================
 * THE MECHANISM — why three one-line probes are the entire gate
 * ============================================================================
 * The collab characters are not store items and are not gated by any receipt, server call,
 * signature check or download. The game simply asks Android whether the partner game is
 * installed, and if it is, hands the character index to its own grant routine:
 *
 * ```
 * Popup::clickUnlock(CCObject*)                          libgame.so 0xa2b1xx
 *   popup type 0x49 (73) → MenuLayer::DownLoadHeadBoxing(int)      0x84b728
 *   popup type 0x53 (83) → MenuLayer::DownLoadBoomTank(int)        0x84b808
 *   popup type 0x42 (66) → MenuLayer::DownLoadBasketBall(int)      0x84b648
 *     │
 *     ├─ GetStaticMethodInfo("com/dnddream/headsoccer/android/headsoccer",
 *     │                      "CheckHeadBoxing"/"CheckBoomTank"/"CheckHeadBasketball", "(I)Z")
 *     ├─ CallStaticBooleanMethod(cls, methodID, 0)        ← the int arg is ignored
 *     │
 *     ├─ true  → SelectLayer::UnlockCharacter(idx)        0xa8f0c8   ✅ grant
 *     │            ├ g_unlockArray[idx] = 1                              (skip if already 1)
 *     │            ├ MenuLayer::SavePlayerUnlock()         0x844700   ← persists to save file
 *     │            └ PlayerSelect::Unlock / SelectCharacter::Unlock  (live UI refresh)
 *     │
 *     └─ false → MenuLayer::MoreGame("com.dnddream.<x>")            ❌ opens Play Store
 * ```
 *
 * `ScoreLayer`/`SelectCharacter` then read that same unlock array, so the character is
 * immediately selectable and stays unlocked across restarts — `SavePlayerUnlock()` *is* the
 * game's own save file, there is no separate "isPremium" style flag that a restart could
 * reset. Because the grant is written to the save file, the unlock survives even without the
 * patch on a later install of the same data.
 *
 * So this patch replaces nothing and stubs nothing: it only makes the shipped probes report
 * "installed". The game then executes its own, unmodified grant chain.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `headsoccer.CheckHeadBoxing(I)Z`      (smali line 623) — `PackageManager` probe of
 *                                         `com.dnddream.headboxing`
 * `headsoccer.CheckBoomTank(I)Z`        (smali line 448) — probe of `com.dnddream.battletank`
 * `headsoccer.CheckHeadBasketball(I)Z`  (smali line 595) — probe of `com.dnddream.HeadBasketball`
 *
 * Each becomes `return true` via `returnEarly(true)` (an injected `const/4 v0, 0x1; return v0`
 * at index 0 of the original body, which stays below as unreachable dead code).
 *
 * **Why all three:** they are the same gate for the three partner games the 7.1.12 roster still
 * contains. `CheckHeadBasketball` is the same mechanism as the two the request named, so
 * leaving it out would ship a patch that unlocks two of three identical characters for no
 * technical reason. Enabling the patch is the user's opt-in for this whole class of content.
 *
 * ============================================================================
 * WHAT THIS DELIBERATELY DOES NOT TOUCH
 * ============================================================================
 * * **`CheckCOB(I)Z` is not patched.** Castle of Burn is not a character gate:
 *   `MenuLayer::DownLoadCOB(int)` (0x84d54c) is a 3-instruction tail-call to
 *   `MenuLayer::MoreGame("com.dnddream.castleofburn2")` — it never calls
 *   `SelectLayer::UnlockCharacter`, and `CheckCOB` only feeds `MenuLayer::CheckCob()`, a
 *   content/event flag. Forcing it true would unlock nothing and would only perturb unrelated
 *   event gating.
 * * The two dead legacy instance twins (`CheckBoxing(I)I`, `CheckTank(I)I`,
 *   `CheckBasketball(I)I`) are **not** touched — none of those names exist in `libgame.so`'s
 *   JNI string table, so nothing calls them.
 * * No native bytes, no manifest entry, no resource — pure DEX.
 *
 * ============================================================================
 * REGISTER BUDGET & INJECTION SAFETY
 * ============================================================================
 * `CheckHeadBoxing` and `CheckBoomTank` are `public static (I)Z` with `.registers 3`
 * (`p0` = ignored int, locals `v0`/`v1`); `CheckHeadBasketball` is `.registers 2`. In all three
 * `v0` is free, so `returnEarly(true)` emits a 4-bit `const/4 v0, 0x1` followed by `return v0`
 * with no register pressure and no `/from16` marshalling.
 *
 * `CheckHeadBoxing`/`CheckBoomTank` contain an inner `:cond_10` label but **no exception table**
 * (zero `.catch` entries — verified in smali), so a labeled block injected at index 0 terminates
 * cleanly; the BurritoBison/Missiles catch-table caveat documented in `CrossyRoadFreeStorePatch.kt`
 * does not apply. `CheckHeadBasketball` is straight-line with no labels at all.
 *
 * ============================================================================
 * CAVEAT — read this before reporting a bug
 * ============================================================================
 * * **It is zero taps — verified on device.** Installed on Waydroid (x86_64, API 33, ARM native
 *   bridge) against 7.1.12: all three characters are already unlocked when the character-select
 *   screen opens, and clearing the app's data and relaunching brings them back unlocked. So the
 *   game itself re-grants them whenever the probes report "installed"; the exact auto-grant call
 *   site was not traced (`PlayerSelect::preCheckUnlock()`, 0xa18d80, only handles the
 *   progression characters 15/17/18, so it is one of the other `SelectLayer::UnlockCharacter`
 *   call sites). The popup **Download** path (`DownLoad*` → probe → `UnlockCharacter(idx)`)
 *   remains a second, equivalent route and now never opens the Play Store either.
 * * The `HeadBoxingEvent` / `Basketball` "install our other game" banners are suppressed as a
 *   side effect: `MenuLayer::LoadImage(int)` (0x84be04/0x84be0c/0x84be4c) reads the same three
 *   probes to decide whether to auto-show them. That is the intended consequence of "the app is
 *   installed" and hides nothing the player can act on.
 * * The characters unlocked are the ones at the indices the popup passes
 *   (`0x49`→73, `0x53`→83, `0x42`→66). They are shipped in the base APK (assets
 *   `assets/0xx.png` and friends) — this patch does not download or fabricate any content.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * One `bytecodePatch` val, `default = true`, independently selectable. No `rawResourcePatch`, no
 * `dependsOn`, no `ResourceMode.RAW_ONLY`, no extensions. Disjoint from `../billing/FreeStorePatch.kt`
 * (different methods on the same class) and `../ads/FreeAdRewardsPatch.kt` (different methods), so
 * all three can be enabled together in any order with no shared offsets.
 *
 * ============================================================================
 * DO NOT
 * ============================================================================
 * * **Never bump `versionCode` (358).** cocos2d-x resolves an expansion OBB by
 *   `main.<versionCode>.com.dnddream.headsoccer.android.obb`; the version code is not ours to
 *   change (same rule as the sibling patches).
 * * Do **not** stub `SelectLayer::UnlockCharacter` or `MenuLayer::SavePlayerUnlock` — they are
 *   the grant and the persistence. This patch only decides *whether* the grant runs.
 * * Do **not** touch `libgame.so`, and do not "simplify" this into a native edit: the DEX probes
 *   are the exact decision point the engine asks, so the DEX patch reaches the same state with
 *   nothing but a readable `return true`.
 * * Do **not** patch `CheckCOB` — see above; it unlocks nothing.
 * * Do not change `returnEarly(true)` to `false`/`returnEarly()` — `false` is the shipped
 *   "not installed" answer and re-opens the Play Store.
 *
 * Re-derived for 7.1.12 against `analysis/head-soccer/` (smali:
 * `classes1/com/dnddream/headsoccer/android/headsoccer.smali`; native addresses and the full
 * `clickUnlock → DownLoad* → Check* → UnlockCharacter → SavePlayerUnlock` trace:
 * `analysis/head-soccer/notes/collab-characters.md`).
 */
@Suppress("unused")
val headSoccerUnlockCollabCharactersPatch = bytecodePatch(
    name = "Unlock Collab Characters",
    description = "Characters that normally want Head Boxing, Boom Tank Showdown or Head " +
        "Basketball installed are unlocked from the start — no other game, no Play Store, " +
        "nothing to download.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HEAD_SOCCER)

    execute {
        // `true` = "partner game installed" ⇒ native takes SelectLayer::UnlockCharacter(idx)
        // and persists via MenuLayer::SavePlayerUnlock(). `false` is the shipped answer and
        // sends the player to the Play Store instead.
        CheckHeadBoxingFingerprint.method.returnEarly(true)
        CheckBoomTankFingerprint.method.returnEarly(true)
        CheckHeadBasketballFingerprint.method.returnEarly(true)
    }
}

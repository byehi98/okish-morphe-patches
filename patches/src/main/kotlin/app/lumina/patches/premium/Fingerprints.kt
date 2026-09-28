package app.lumina.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Premium gating in Lumina Wallpapers 1.0.2.6 is 100% client-side (notes:
 * `analysis/lumina/notes/premium-bypass.md` §1/§"Gate enforcement model").
 *
 * Shape of the model:
 *   - ONE central predicate — `id.b.c()Z` (prefs "lifetime" OR User.isUpgraded +
 *     userId != "0") — every premium decision consults it (11 call sites).
 *   - `Wallpaper.isPremium` / `Category.isPremium` are server-delivered model
 *     getters used ONLY by click-gates and lock-overlay decorations — no list is
 *     ever filtered, so forcing them to `false` is enough.
 *   - `Category.isPurchased` is a 4th, independent server flag ("belt and braces",
 *     notes §4 / technique-survey §C2) — all 3 callers treat `true` = purchased.
 *
 * The defining class of the central gate is R8-renamed (`Lid/b;`) and MUST NOT be
 * named in the fingerprint — it is matched purely by its ordered body filters,
 * which were proven unique across all 3 DEXes (see below).
 */

/**
 * T1 — central lifetime-premium gate: `public final c()Z`
 * (`classes3/id/b.smali:336`, `.registers 4` → `v0..v2` free, `p0` = `v3`).
 *
 * Returns true if SharedPreferences `"lifetime"` is set OR
 * `User.isUpgraded() && User.getUserId() != "0"`. 11 callers — one override
 * unlocks every paywall, click-gate and lock flag in the app.
 *
 * Uniqueness (independently re-verified on 1.0.2.6): the only method in the
 * whole APK that contains, in one body, `SharedPreferences;->getBoolean` +
 * `User;->isUpgraded` + `const-string "lifetime"` is `id.b.c()Z`
 * (files also holding both markers — `gd/o`, `LoginActivity`,
 * `CollectionActivity` — have them in different methods).
 *
 * Filter order mirrors the smali instruction order exactly:
 *   iget-object SharedPreferences → const-string "lifetime" → getBoolean →
 *   User.isUpgraded → User.getUserId.
 * `definingClass = "Lid/b;"` deliberately omitted (R8-renamed, would break on
 * every rebuild) — accessFlags PUBLIC+FINAL + the ordered body identify it.
 *
 * Patch: `const/4 v0, 0x1; return v0` — register-safe at offset 0
 * (`.registers 4`, `v0` exists, `const/4` value 1 fits).
 */
object LifetimePremiumCheckFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/content/SharedPreferences;"),
        string("lifetime"),
        methodCall(definingClass = "Landroid/content/SharedPreferences;", name = "getBoolean"),
        methodCall(definingClass = "Lcom/lumina/wallpapers/data/models/User;", name = "isUpgraded"),
        methodCall(definingClass = "Lcom/lumina/wallpapers/data/models/User;", name = "getUserId"),
    ),
)

/**
 * T2 — `Wallpaper.isPremium()Z` (`classes3/.../data/models/Wallpaper.smali:2065`,
 * `.registers 2` → `v0` free).
 *
 * Server JSON flag (`@SerializedName("isPremium")`), read-only getter, never
 * written client-side. 4 callers: 2 click-gates (AiWallpaperActivity,
 * SearchActivity: `!isPremium || perCollection || lifetime → open`) and 2 grid
 * adapters drawing the lock overlay. No caller filters a list.
 *
 * Patch: `const/4 v0, 0x0; return v0` — register-safe at offset 0.
 */
object WallpaperIsPremiumFingerprint : Fingerprint(
    definingClass = "Lcom/lumina/wallpapers/data/models/Wallpaper;",
    name = "isPremium",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
)

/**
 * T3 — `Category.isPremium()Z` (`classes3/.../data/models/Category.smali:1354`,
 * `.registers 2` → `v0` free).
 *
 * Server JSON flag; 3 callers, all decoration/gating: CollectionActivity click
 * gate (paywall dialog), category-grid premium badge, and the "locked" flag
 * passed to the wallpaper adapter. No list filtering.
 *
 * Patch: `const/4 v0, 0x0; return v0` — register-safe at offset 0.
 */
object CategoryIsPremiumFingerprint : Fingerprint(
    definingClass = "Lcom/lumina/wallpapers/data/models/Category;",
    name = "isPremium",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
)

/**
 * T4 (optional, belt-and-braces) — `Category.isPurchased()Z`
 * (`classes3/.../data/models/Category.smali:1365`, `.registers 2` → `v0` free).
 *
 * Server-driven flag (`@SerializedName("isPurchased")`, delivered per profile_id
 * by `https://lumina.plte.link/api/categories`). Only ONE method named
 * `isPurchased` exists in the whole APK (this one) → definingClass+name is unique.
 *
 * Return direction `true` = purchased, verified in smali at ALL 3 callers
 * (notes §4 direction resolved — not guessed):
 *   1. `ed/c.smali:885` CollectionActivity click gate —
 *      `if-nez v5(isPurchased), :cond_2af` → `:cond_2af` builds the launch Intent
 *      (OPEN path); false falls through to lifetime/perCollection, else `:cond_1b3`
 *      = paywall dialog.  → true opens the gate.
 *   2. `yc/b.smali:412` category-grid badge —
 *      `isPurchased || lifetime(v7 = id.b.c()) || perCollection` → `ai_unlock`
 *      drawable (0x7f0700bc), else `ai_lock` (0x7f0700b9).  → true = unlocked badge.
 *   3. `CollectionActivity.smali:1985` "locked" flag passed as the Z param of
 *      `Lyc/i;-><init>(Context;Ll1/h0;IZI)V` —
 *      `isPurchased || perCollection || lifetime` → `v4 = false` (unlocked);
 *      otherwise `v4 = isPremium`.  → true clears the lock.
 *
 * Redundant once T1/T3 land (every reading gate short-circuits), but harmless
 * and it keeps badges/UI consistent if hook 1 ever fails to match or the server
 * withholds the flag for guest profiles. Red fields read the FIELD directly
 * (equals/hashCode/parcel) — the getter override touches only external readers.
 *
 * Patch: `const/4 v0, 0x1; return v0` — register-safe at offset 0.
 */
object CategoryIsPurchasedFingerprint : Fingerprint(
    definingClass = "Lcom/lumina/wallpapers/data/models/Category;",
    name = "isPurchased",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
)

package app.appusage.patches.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Entitlement model (App Usage 6.20) ───────────────────────────────────────
// DexGuard re-maps every identifier on each release, so the 6.19 names
// (`Lgln;`, fields 癰/襭/驄/鷛, methods 驄()/鷛()) are gone in 6.20. The shape is
// unchanged, only the identifiers moved:
//
//   Lgln; (6.19)                → Lbgc; (6.20)
//   field 癰 purchased          → 轛   (read as "already bought" by the dialog)
//   field 襭 free/unlicensed    → 魒   ← master lock
//   field 驄 ad-free trial      → 驔   (written by AdFreeRewardedVideoWnd)
//   field 鷛 sync               → 孌   (cleared by the reset paths)
//   method 驄(Context) isPro    → 驔(Context)   (nextInt(800)  < 456)
//   method 鷛(Context) paywall  → 孌(Context)   (nextInt(1000) < 543)
//
// (DexGuard does not preserve field declaration order, so the names above are
// derived from usage, not from the `<init>` order — the `Lbgc` constructor
// allocates them in the order 轛, 孌, 驔, 魒.)
//
// `Lbgc` is the only singleton holding four AtomicBoolean entitlement flags
// (verified: exactly five `魒.set(...)` call sites exist in the whole DEX), and
// both accessors are still the same anti-analysis coin flip:
//
//   驔: return nextInt(800)  < 456 ? packageName == gs.鸇 (".pro" build) : !魒.get()
//   孌: return nextInt(1000) < 543 ? 魒.get()                            : packageName == gs.耰
//
// so the answer flips at random between launches on the free package. Forcing
// the two methods deterministically is mandatory — pinning `魒` alone would leave
// ~57% of the gates answering "not pro".
//
// `魒` is only ever set to true in five places, and four of them are inside the
// anti-tamper `if (hash != -1105923880)` branch (LicWnd.ఓ ×2, ixm.<init>,
// AppOpts2Wnd.onDestroy), which "Spoof original signature" makes unreachable.
// The remaining one is the unconditional write in `Lgs;->onCreate()V` below,
// which this patch removes.

/**
 * `Lbgc;->驔(Landroid/content/Context;)Z` — isPro (6.19 `Lgln;->驄`).
 *
 * smali: classes/bgc.smali:336. `.registers 4`.
 * Anchor: `const/16 v1, 0x320` + `Ljava/util/Random;->nextInt(I)I`.
 */
object IsProFingerprint : Fingerprint(
    definingClass = "Lbgc;",
    name = "驔",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
)

/**
 * `Lbgc;->孌(Landroid/content/Context;)Z` — free-build / paywall gate
 * (6.19 `Lgln;->鷛`).
 *
 * smali: classes/bgc.smali:261. `.registers 4`.
 * Anchor: `const/16 v1, 0x3e8` + `Ljava/util/Random;->nextInt(I)I`.
 */
object IsFreeBuildFingerprint : Fingerprint(
    definingClass = "Lbgc;",
    name = "孌",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
)

/**
 * `Ldfw;->臡(Landroid/app/Activity;)V` — "Upgrade to PRO" dialog (6.19 `Lisc;->躤`).
 *
 * smali: classes/dfw.smali:3864-ish, `.registers 12`, 215 instructions.
 * Body starts with `if (p0 == null) return; if (!魒.get()) return; if (轛.get()) return;`
 * and then inflates the dialog that logs `show_purchase_dlg` (const-string at
 * line 3890). Called from 18 windows.
 */
object ShowPurchaseDialogFingerprint : Fingerprint(
    definingClass = "Ldfw;",
    name = "臡",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;"),
)

/**
 * `Lgs;->onCreate()V` — the Application class (6.19 `Lbob;`). `Lgs` is abstract
 * and instantiated as `com.a0soft.gphone.uninstaller.free.MainApp` (and the
 * `:bg` process variant); it also owns the `Random` used by `Lbgc`.
 *
 * smali: classes2/gs.smali:628. `.registers 14`, public final, one `return-void`
 * at the end and a single `AtomicBoolean->set(Z)V` call in the whole method:
 *
 *   905  sget-object v7, Lbgc;->瓘:Lbgc;
 *   909  iget-object v7, v7, Lbgc;->魒:Ljava/util/concurrent/atomic/AtomicBoolean;
 *   913  invoke-virtual {v7, v6}, Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V
 *
 * `v6` is `const/4 v6, 0x1` (line 653) and is still 1 there, but it is reused
 * later in the method (lines 1230, 1248, 1373), so the value register cannot be
 * overwritten with 0. Removing the invoke is the only edit that leaves every
 * other register intact.
 */
object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lgs;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

package app.appusage.patches.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Entitlement model (App Usage 6.19) ───────────────────────────────────────
// The license state lives in a tiny singleton, `Lgln;` (jadx `p000.gln`, the
// `SAM`-compiled helper). It owns four AtomicBoolean flags and two accessors:
//
//   fields (all default false, set in `<init>`):
//     Lgln;->癰  AtomicBoolean  = purchased          (jadx f36049)
//     Lgln;->襭  AtomicBoolean  = free/unlicensed    (jadx f36050)   ← master lock
//     Lgln;->驄  AtomicBoolean  = ad-free trial      (jadx f36051)
//     Lgln;->鷛  AtomicBoolean  = sync in progress   (jadx f36052)
//
//   methods:
//     Lgln;->驄(Context)Z  = isPro       (jadx m13413)
//     Lgln;->鷛(Context)Z  = free-build / show-paywall gate (jadx m13414)
//
// Both accessors are written as an anti-analysis coin flip:
//
//   驄: return nextInt(800)  < 456 ? packageName == bob.鑉 (".pro" build) : !襭.get()
//   鷛: return nextInt(1000) < 543 ? 襭.get()                     : packageName == bob.ఢ
//
// so on the free package the "correct" answer flips at random between launches.
// Forcing the two methods deterministically is therefore mandatory — pinning the
// `襭` field alone would leave ~57% of the gates answering "not pro".
//
// `襭` is only ever set to true in four places, and three of them are inside the
// anti-tamper `if (hash != -1105923880)` branch (LicWnd.躚, hor.<init>,
// AppOpts2Wnd.onDestroy), which the "Spoof original signature" patch makes
// unreachable. The remaining one is the unconditional write in
// `Lbob;->onCreate()V` below, which this patch removes; `襭` then keeps its
// initial value `false` for the whole process lifetime, which is what the 30+
// direct `f36050.get()` limit checks in the UI read.
//
// `Lisc;->躤(Activity)V` is the actual upsell dialog (anchor string
// "show_purchase_dlg", called from 20+ windows). It already no-ops when `襭` is
// false, but it is patched as well so that no later state flip can surface it.

/**
 * `Lgln;->驄(Landroid/content/Context;)Z` — isPro (jadx `gln.m13413`).
 *
 * smali: classes/gln.smali:261. `.registers 4`.
 * Callers: 26 sites, almost always as `m13413(ctx) || 癰.get()` (or `|| 驄.get()`).
 */
object IsProFingerprint : Fingerprint(
    definingClass = "Lgln;",
    name = "驄",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
)

/**
 * `Lgln;->鷛(Landroid/content/Context;)Z` — free-build / paywall gate (jadx `gln.m13414`).
 *
 * smali: classes/gln.smali:350. `.registers 4`.
 * Callers: 26 sites, used as `if (m13414(ctx) && !癰.get())` → show ads/paywall.
 */
object IsFreeBuildFingerprint : Fingerprint(
    definingClass = "Lgln;",
    name = "鷛",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
)

/**
 * `Lisc;->躤(Landroid/app/Activity;)V` — "Upgrade to PRO" dialog.
 *
 * smali: classes/isc.smali:6175. `.registers 12`.
 * Body starts with `if (p0 == null) return; if (!襭.get()) return; if (癰.get()) return;`
 * and then inflates the dialog that logs `show_purchase_dlg` (const-string at 6298).
 */
object ShowPurchaseDialogFingerprint : Fingerprint(
    definingClass = "Lisc;",
    name = "躤",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/app/Activity;"),
)

/**
 * `Lbob;->onCreate()V` — the Application class (`bob` is abstract, instantiated as
 * `com.a0soft.gphone.uninstaller.free.MainApp`; `com.a0soft.gphone.uninstaller.bg.*`
 * for the `:bg` process).
 *
 * smali: classes2/bob.smali:627. `.registers 14`, public final, one `return-void`
 * at the end (line 1393) and a single `AtomicBoolean->set(Z)V` call in the whole
 * method (line 912):
 *
 *   902  sget-object v7, Lgln;->豅:Lgln;
 *   906  iget-object v7, v7, Lgln;->襭:Ljava/util/concurrent/atomic/AtomicBoolean;
 *   912  invoke-virtual {v7, v6}, Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V
 *
 * `v6` is `const/4 v6, 0x1` (line 652) and is still 1 there — and, importantly,
 * it is reused at the very end of the method (`iput-boolean v6, v8, Lbob;->鱳:Z`),
 * so the value register cannot simply be overwritten with 0. Removing the invoke
 * is the only edit that leaves every other register intact.
 */
object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lbob;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

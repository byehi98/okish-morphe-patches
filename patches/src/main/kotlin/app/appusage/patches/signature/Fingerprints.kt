package app.appusage.patches.signature

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Signing-certificate accessors (App Usage 6.20) ───────────────────────────
// The app never compares certificate *names*: it compares two derived integers
// against constants baked into the DEX.
//
//   Arrays.hashCode(certDER)                    == -1105923880   (Signature.hashCode)
//   String.hashCode(SHA1hex(certDER).uppercase) == 559284713
//
// Both are properties of the ORIGINAL certificate, which is still recoverable
// from the APK Signing Block of the shipped bundle (v2/v3 only; the APK ships no
// v1 signature files). Re-signing with a different key therefore fails every
// check, and app-wide that is not cosmetic:
//
//   * the `icr`/`hx` path → schedules an `Lhx` coroutine that sleeps 1933 ms and
//     then calls `Ly31;->ఓ()V`, i.e. Process.killProcess(myPid) +
//     System.exit(10). This is the ~2 s anti-tamper kill that fires as soon as a
//     window is destroyed (MainWnd → DashboardWnd).
//   * LicWnd.ఓ / ixm.<init> / AppOpts2Wnd.onDestroy → flip the `Lbgc` free
//     flag (魒) back on.
//   * widget providers / workers, export, history queries → degraded paths.
//
// 6.20 re-mapped every DexGuard identifier (6.19: `Lgi0;->鷛` Signature accessor,
// `Lv31;->襭` byte accessor, `Lp31` wrapper). The 6.20 chain is:
//
//   Lrf0;->孌(Context)Signature        ← the only direct PackageManager accessor
//     ↑
//     Lj11;->灒(Context)[B             ← cert DER, cached in j11.齶
//       ↑
//       Le11;->ڡ(Context)[B            (raw bytes)   → Lyf2;->驔([B)String (SHA-1)
//       Le11;->鶱(Context)Signature    (new Signature(bytes))
//       Le11;->鱮()Z                   (cache non-null)
//
// Every one of the 21 hash-constant sites resolves through `Lrf0;->孌` (directly
// or via `Lj11;->灒`), so returning the original DER at these two points satisfies
// all of them at the source.
//
// Target A — smali classes/rf0.smali:7
//     .method public static 孌(Landroid/content/Context;)Landroid/content/pm/Signature;
//   Callers: kb2, ezg, bsz, tx0, LicWnd, and `Lj11;->灒` itself.
//
// Target B — smali classes/j11.smali:3899
//     .method public final 灒(Landroid/content/Context;)[B
//   Callers: e11.ڡ, e11.鶱, djy. `j11` is a DexGuard-merged class (it implements
//   ~11 unrelated interfaces), but 灒 is the only cert-bytes accessor in it.
//
// Class/parameter names are DexGuard output, so the fingerprints intentionally
// pin the 6.20 mapping (the patch is declared compatible with 6.20 only);
// return type + parameter list keep them unambiguous inside their classes.

/** `Lrf0;->孌(Landroid/content/Context;)Landroid/content/pm/Signature;` */
object GetSignatureFingerprint : Fingerprint(
    definingClass = "Lrf0;",
    name = "孌",
    returnType = "Landroid/content/pm/Signature;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
)

/** `Lj11;->灒(Landroid/content/Context;)[B` — cert DER, cached in `j11.齶`. */
object GetSignatureBytesFingerprint : Fingerprint(
    definingClass = "Lj11;",
    name = "灒",
    returnType = "[B",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
)

package app.appusage.patches.signature

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Signing-certificate accessors (App Usage 6.19) ───────────────────────────
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
//   * icr.onCreate / icr.onDestroy  → schedule an `Lhx` coroutine that sleeps
//     1933 ms and then calls `Ly31;->ఓ()V`, i.e. Process.killProcess(myPid) +
//     System.exit(10). This is the ~2 s anti-tamper kill that fires as soon as a
//     window is destroyed (MainWnd → DashboardWnd), so a re-signed build dies a
//     few seconds after launch.
//   * LicWnd / hor / AppOpts2Wnd    → flip the `gln` free-version flag back on.
//   * widget providers / workers, export, history queries → take degraded paths.
//
// Returning the original DER from the two accessors below satisfies every one of
// those checks at the source, so no per-call-site branch surgery is needed.
//
// Target A — jadx `p000.gi0.m13282` (the only Signature-returning method in gi0):
//   smali classes/gi0.smali:137
//     .method public static 鷛(Landroid/content/Context;)Landroid/content/pm/Signature;
//   Callers: icr:380, LicWnd:523, gwd:146, bd2:319 (direct PackageManager path).
//
// Target B — jadx `p000.v31.m20142` (the only byte[]-returning method with a
// Context parameter in v31):
//   smali classes/v31.smali:2902
//     .method public final 襭(Landroid/content/Context;)[B
//   Everything that goes through `p31` funnels here: `p31.鷚` (m18345, bytes)
//   and `p31.黂` (m18347, `new Signature(bytes)`), which is what the
//   `lh2.m17369(...)` SHA-1 sites and the widget workers use.
//
// Class/parameter names are DexGuard output, so the fingerprints intentionally
// pin the 6.19 mapping (the patch is declared compatible with 6.19 only);
// return type + parameter list keep them unambiguous inside their classes.

/** `Lgi0;->鷛(Landroid/content/Context;)Landroid/content/pm/Signature;` */
object GetSignatureFingerprint : Fingerprint(
    definingClass = "Lgi0;",
    returnType = "Landroid/content/pm/Signature;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
)

/** `Lv31;->襭(Landroid/content/Context;)[B` — instance method of the cert singleton. */
object GetSignatureBytesFingerprint : Fingerprint(
    definingClass = "Lv31;",
    returnType = "[B",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
)

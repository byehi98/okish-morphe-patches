package app.docscanner.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Premium gates (6.9.16 / versionCode 70196) ───────────────────────────────
// The entitlement logic still lives in one obfuscated R8 class, but it was
// renamed and restructured again since 6.9.14:
//
//   6.9.14  classes3/n78.smali   a() = subscription, b() = master (IS_DONATED)
//   6.9.16  classes3/b88.smali   a() = subscription (PRE_ACC_KEY + SKU list)
//                                b() = master = c() || a()
//                                c() = one-time/donated (PRE_ACC_KEY + IS_DONATED)
//                                d() = "any subscription SKU owned" helper
//                                e(String) = owned + failed-check grace window
//                                f()J = failed-check timestamp
//
// The old subscription fingerprint anchored on `Luia;->a(Ljava/lang/String;)Z`
// (the owned-SKU helper). That helper class no longer exists in 6.9.16 — `uia`
// is now an unrelated collection type — so the 6.9.14 fingerprint matched
// nothing and `matchAll()` aborted the patch before any instruction was added
// ("Changed 0 of 45372 classes").
//
// Instead of hard-coding obfuscated class/method names, anchor on the one
// stable, human-readable marker the R8 config keeps: the `IS_DONATED` preference
// key. In 6.9.16 it appears in exactly three methods app-wide, and only one of
// them is a no-arg `public static boolean` — the gate class' c() — which also
// tells us the class that owns the whole entitlement model. The patch code then
// forces every no-arg boolean entry gate in that class to true.

private val gateAccessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)

/**
 * Anchor for the premium gate class.
 *
 * MATCH STRATEGY — `IS_DONATED` occurs in:
 *   - b88.c()                                public static Z(), no params  ← match
 *   - tc9.l(Lg19;)Z                          has a parameter               ← filtered out
 *   - proApp/b.c(Landroid/billingclient/api/Purchase;Z)Z  has params       ← filtered out
 *
 * The empty parameter list + public static + boolean return filters therefore
 * leave exactly one match in the whole app, independent of the obfuscated name.
 *
 * Patch: `const/4 v0, 0x1; return v0` injected at index 0 of every no-arg
 * boolean entry gate in the matched class (see DocScannerPremiumPatch). Register
 * safety: all gates are static with no parameters, so v0 is always a local, and
 * every gate declares at least three locals (.locals 4/1/5/3 for a/b/c/d).
 * None of them contains a try/catch or monitor, so an index-0 injection cannot
 * strand a handler or a lock.
 */
object PremiumGateFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = gateAccessFlags,
    parameters = listOf(),
    filters = listOf(
        string("IS_DONATED")
    )
)

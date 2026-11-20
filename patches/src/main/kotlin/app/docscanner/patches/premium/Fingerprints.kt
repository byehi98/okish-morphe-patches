package app.docscanner.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Premium gates (6.9.14) ────────────────────────────────────────────────────
// The R8 class holding the premium logic moved from `bm7` (6.9.9, classes2) to
// `n78` (6.9.14, classes3). The two gates are still `a()Z` and `b()Z`:
//
//   classes3/n78.smali:82   .method public static a()Z   ← subscription gate
//   classes3/n78.smali:204  .method public static b()Z   ← master "any paid state" gate
//
// The old class anchor (a class containing the two one-time SKU strings) no
// longer works: on 6.9.14 three classes (dc9, zl7, n78) contain
// "com.cv.proversion" + "com.cv.large_amount", and Morphe resolves the class
// anchor to the first one in DEX order (dc9), so the method filters then miss.
// The fingerprints below therefore match methods directly, using markers that
// are unique to the gates and do not depend on the current obfuscated names.

private val gateAccessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)

/**
 * Target A — `n78.b()` — the master premium gate.
 *
 * Returns true if PRE_ACC_KEY OR IS_DONATED OR an owned one-time SKU
 * (com.cv.proversion / com.cv.large_amount) OR the a() subscription fallback.
 * Every ad surface, paywall, pro feature and the Firebase pro/free topic
 * selection branches on this method, so forcing it true grants premium and
 * removes all ads as a side effect.
 *
 * MATCH STRATEGY — "IS_DONATED" is read only inside b(). The string also
 * occurs in dc9 (inside `l(Lr09;)Z`, which has a parameter) and in zl7 (inside
 * a constructor). Both are excluded by the empty parameter list + public
 * static + boolean return filters, so b() is the only match in the whole app.
 *
 * Patch: `const/4 v0, 0x1; return v0` injected at index 0. Register safety:
 * static method with `.registers 3` → v0..v2 are locals, v0 fits the 4-bit
 * range of const/4. No try/catch and no monitor anywhere in the method, so an
 * index-0 injection cannot strand a handler or lock.
 */
object IsPremiumFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = gateAccessFlags,
    parameters = listOf(),
    filters = listOf(
        string("IS_DONATED")
    )
)

/**
 * Target B — `n78.a()` — the subscription / access gate.
 *
 * Returns true if PRE_ACC_KEY OR any subscription SKU is owned (quarterly /
 * yearly, the Ln78;->a list built in <clinit>). Direct callers: zl7 (2),
 * b()'s fallback at n78.smali:360, and CustomThemeActivity (custom theme
 * unlock), so it is patched in addition to b().
 *
 * MATCH STRATEGY — PRE_ACC_KEY is read by both a() and b(), but only those two
 * methods in the whole app combine it with a call to the owned-SKU helper
 * `Luia;->a(Ljava/lang/String;)Z` (6.9.9 name: `Lpr9;`). The filter list gives
 * both methods; the patch code de-duplicates against Target A, which leaves
 * a() as the only extra target. No obfuscated class name is referenced.
 *
 * Patch: `const/4 v0, 0x1; return v0` at index 0. Register safety: static
 * method with `.registers 5` → v0..v4 are locals, v0 is valid for const/4.
 * No try/catch / monitor in the method.
 */
object IsSubscribedFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = gateAccessFlags,
    parameters = listOf(),
    filters = listOf(
        string("PRE_ACC_KEY"),
        methodCall(
            definingClass = "Luia;",
            name = "a",
            returnType = "Z"
        )
    )
)

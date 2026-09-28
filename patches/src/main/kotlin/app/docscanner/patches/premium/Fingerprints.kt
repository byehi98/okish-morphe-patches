package app.docscanner.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Class anchor — product IDs in bm7's <clinit> ─────────────────────────────
// Unchanged from the proven 6.8.18 patch. The two one-time SKU IDs are written
// in defpackage.bm7's static constructor (classes2/bm7.smali:61 and :70) and
// feed the Lbm7;->b (owned-SKU) list.
//
// On 6.9.9 these strings also occur in bb7 (classes4) and xm8 (classes2), but
// Morphe scans DEX in order — bm7 is the first match (classes2, index 188;
// xm8 at 8187; bb7 lives in the later classes4) — so both method fingerprints
// below resolve inside bm7, which is exactly the class we want.
private val premiumClassFingerprint = Fingerprint(
    strings = listOf("com.cv.proversion", "com.cv.large_amount")
)

/**
 * Target A — `bm7.b()` — the master premium gate (50 call sites).
 *
 * Confirmed smali: classes2/bm7.smali:204
 *   `.method public static b()Z` / `.registers 3`
 *
 * Returns true if PRE_ACC_KEY OR IS_DONATED OR an owned one-time SKU
 * (com.cv.proversion / com.cv.large_amount) OR the a() subscription fallback.
 * Every ad surface, paywall, pro feature and the Firebase pro/free topic
 * selection branches on this method, so forcing it true grants premium and
 * removes all ads as a side effect.
 *
 * MATCH STRATEGY — `string("IS_DONATED")` is read only inside b()
 * (bm7.smali:267, after the PRE_ACC_KEY check at :232). a() reads PRE_ACC_KEY
 * but never IS_DONATED, and <clinit> holds neither, so within the anchored
 * class the filter uniquely selects b() — no obfuscated name is referenced.
 * Single filter → instruction-order matching is trivially satisfied.
 *
 * Patch: `const/4 v0, 0x1; return v0` injected at index 0. Register safety:
 * static method with `.registers 3` → v0..v2 are locals, v0 fits the 4-bit
 * range of const/4. No try/catch and no monitor anywhere in bm7.smali, so an
 * index-0 injection cannot strand a handler or lock.
 */
object IsPremiumFingerprint : Fingerprint(
    classFingerprint = premiumClassFingerprint,
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("IS_DONATED")
    )
)

/**
 * Target B — `bm7.a()` — the subscription / access gate.
 *
 * Confirmed smali: classes2/bm7.smali:82
 *   `.method public static a()Z` / `.registers 5`
 *
 * Returns true if PRE_ACC_KEY OR any subscription SKU is owned (quarterly /
 * yearly, the Lbm7;->a list built in <clinit>). Direct callers: bb7 (2),
 * b()'s fallback at bm7.smali:360, and CustomThemeActivity (custom theme
 * unlock).
 *
 * MATCH STRATEGY — the SKU-ownership check `invoke-virtual {v4, v3},
 * Lpr9;->a(Ljava/lang/String;)Z` (bm7.smali:185) is the distinguishing call.
 * b() also contains an Lpr9;->a call (bm7.smali:340), but a() is declared
 * first in the class (line 82 vs 204) and baksmali preserves DEX order, so
 * the first match inside the anchored class is a(); b() is covered separately
 * by Target A above. Patching BOTH gates makes the result robust even if a
 * future update reorders or inlines the b() → a() delegation.
 *
 * Patch: `const/4 v0, 0x1; return v0` at index 0. Register safety: static
 * method with `.registers 5` → v0..v4 are locals, v0 is valid for const/4.
 * No try/catch / monitor in the method.
 */
object IsSubscribedFingerprint : Fingerprint(
    classFingerprint = premiumClassFingerprint,
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lpr9;",
            name = "a",
            returnType = "Z"
        )
    )
)

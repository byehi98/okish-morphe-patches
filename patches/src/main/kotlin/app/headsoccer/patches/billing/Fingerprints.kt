package app.headsoccer.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ── Head Soccer 7.1.6 (versionCode 345) — DEX-only fingerprints ────────────
//
// Verified against the shipped smali:
//   analysis/head-soccer/smali/classes/com/dnddream/headsoccer/android/headsoccer.smali
//   analysis/head-soccer/smali/classes/com/dnddream/headsoccer/android/MyBillingImpl.smali
//
// The app ships with DEBUG-STYLE NAMES throughout (`TAG = "Headsoccer-Billing4"`,
// `getSkuByIndex`, `launchBillingFlow`, `billingCompleteIAP`, `PurchasedFunc`) —
// this is a long-lived legacy app that has never been ProGuarded, so the class and
// method names below are the literal smali names, not obfuscated ones.
//
// Neither fingerprint relies on those app-private names ALONE: each is anchored on
// a THIRD-PARTY call that can never be obfuscated (`com.android.billingclient` is
// Google's own library, `com.dnddream…MySku` is an enum). See the DO-NOT section of
// FreeStorePatch.kt for the deliberate choice of primary vs fallback anchors.

/**
 * `headsoccer.onBuyPoint(int)V` — smali line 4771, `.registers 8`, **NO try/catch table**.
 *
 * The single funnel every store row tap reaches. Its *only* action in all 8 branches is
 * `MyBillingImpl.launchBillingFlow(Activity, String, String[])`; native gets here via
 *
 *   native Store::Buy / Store::BuyCharacter / Store::BuyPresnet
 *     → headsoccer.JavaSelectItemFunc(int)          (JNI, GL thread)
 *       → headsoccer$10.run() → headsoccer.onBuyPoint(int)     (headsoccer$10.smali:48)
 *
 * so patching this one method intercepts the whole store. All 13 `MySku` ordinals are
 * reachable through it (native can only pass 0…7, 11, 12, 31, 32, 33 — the 31/32/33 tags
 * are remapped to SKUs 8/9/10 inside the method itself).
 *
 * Method-body injection is safe here: no exception table, so morphe's instruction-tree
 * writer merges labeled blocks intact (the BurritoBison/Missiles caveat only bites when a
 * method HAS catches — see CrossyRoadFreeStorePatch.kt).
 */
object OnBuyPointFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/dnddream/headsoccer/android/headsoccer;",
    name = "onBuyPoint",
    filters = listOf(
        // The Play-SKU lookup + the billing launch. These two co-occur in exactly one
        // method in the whole DEX.
        methodCall(
            definingClass = "Lcom/dnddream/headsoccer/android/MySku;",
            name = "getSkuByIndex"
        ),
        methodCall(
            definingClass = "Lcom/dnddream/headsoccer/android/MyBillingImpl;",
            name = "launchBillingFlow"
        ),
    )
)

/**
 * `MySecurity.verifyPurchase(String, String)Z` — smali in MySecurity.smali,
 * `.registers 8`, public static.
 *
 * The legacy LVL `IABUtil/Security` RSA-2048 / SHA1withRSA receipt check over
 * `purchase.getOriginalJson()`. Returns `false` on any failure — SILENTLY (just a
 * `Log.e`, no dialog, no exit), so a failed check simply never grants.
 *
 * **The embedded key is the Play Console app-signing licence key, NOT the APK signing
 * certificate.** The APK is `CN=D&D Dream`, SHA-1 `F9:59:B7:25:BE:10:B4:71:05:C1:51:0E:39:
 * AA:74:58:E9:FB:97:A9`, 1024-bit RSA — the developer's lifetime upload key. The two
 * have no relationship, so there is no cert-swap trick available here; forcing the
 * return value is the only correct bypass.
 *
 * Paired with `MyBillingImpl.isSignatureValid(Purchase)` (T-07 in targets.md), which is
 * the only caller and simply logs + skips on `false`.
 */
object VerifyPurchaseFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    definingClass = "Lcom/dnddream/headsoccer/android/MySecurity;",
    name = "verifyPurchase",
    filters = listOf(
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "isEmpty"
        ),
        methodCall(
            definingClass = "Lcom/dnddream/headsoccer/android/MySecurity;",
            name = "generatePublicKey"
        ),
        methodCall(
            definingClass = "Lcom/dnddream/headsoccer/android/MySecurity;",
            name = "verify"
        ),
    )
)
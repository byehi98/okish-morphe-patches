package app.swampattack.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ── Swamp Attack 4.8.7.0 (versionCode 724) — billing fingerprints ──────────
//
// The game package `com.libo7.swampattack.*` ships UNOBFUSCATED (R8 only shrunk
// third-party SDKs), so the names below are the literal smali names. Each
// fingerprint is still anchored on more than the name alone: the filters pin the
// calls that co-occur in exactly one method of the whole APK.
//
// Verified against:
//   analysis/swampattack/smali/classes14/com/libo7/swampattack/NativeInterface.smali
//   analysis/swampattack/decompiled/sources/com/libo7/swampattack/NativeInterface.java

/**
 * `NativeInterface.startPurchasing(String)V` — smali `NativeInterface.smali:2446`,
 * `.registers 9`, `public final`, **no try/catch table**.
 *
 * The single choke point of the whole purchase funnel:
 *
 * ```
 * native buy button (liblibO7.so, GS_PurchaseProduct)
 *   → libO7.libO7_IAP_Buy(String)             libO7.smali:1431   (only Java call site)
 *     → NativeInterface.startPurchasing(String)                  ← THIS METHOD
 *       → BillingManager.purchase$default(activity, productId, false)
 *         → GooglePlayBillingProvider.purchase()  OR region-gated Yandex Pay
 *           → sendSuccessToGame → server `validateIAP` cloud fn
 *             → sendSuccessToGameAfterValidation → native event 0x3f
 * ```
 *
 * The `BillingManager.purchase$default` call is this method's ONLY invoke and is
 * reachable from exactly one place in the APK — Google's `com.android.billingclient`
 * references can never be obfuscated away, so the filter stays stable across builds.
 */
object StartPurchasingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    definingClass = "Lcom/libo7/swampattack/NativeInterface;",
    name = "startPurchasing",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/libo7/swampattack/billing/BillingManager;",
            name = "purchase\$default",
        ),
    ),
)

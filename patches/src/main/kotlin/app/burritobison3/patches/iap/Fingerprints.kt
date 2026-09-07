package app.burritobison3.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Kongregate MTX inventory check — the game's purchase/inventory API impl
 * (`hasItem(Ljava/lang/String;)Z`, smali-verified in
 * classes3/com/kongregate/android/internal/sdk/b$c.smali):
 *
 *   user-id via Long → zero-check → ConcurrentHashMap.get(userId) →
 *   Collection.iterator() → String.equals(itemId) loop, returns 0x1 on match.
 *
 * Fingerprint anchors ONLY on stable java.* SDK calls (in exact smali order).
 * The defining class (b$c) and helper classes (o/i/b, o/i/c) are obfuscated
 * and deliberately NOT referenced.
 */
object MtxHasItemFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/ConcurrentHashMap;",
            name = "get"
        ),
        methodCall(
            definingClass = "Ljava/util/Collection;",
            name = "iterator"
        ),
        methodCall(
            definingClass = "Ljava/lang/String;",
            name = "equals"
        )
    )
)

/**
 * Unity → Kongregate bridge (smali-verified in
 * classes3/com/kongregate/android/internal/sdk/unity/UnityWrapper.smali):
 *
 *   .method public KongregateAPIMtxHasItem(Ljava/lang/String;)Z
 *     KongregateAPI;->mtx() → MicrotransactionServices;->hasItem(p1)
 *
 * The bridge method name must match the C# (IL2CPP) side via JNI, so it is
 * never obfuscated — safe to pin by defining class + name. Filters confirm
 * the mtx() → hasItem() delegation order.
 */
object UnityMtxHasItemFingerprint : Fingerprint(
    definingClass = "Lcom/kongregate/android/internal/sdk/unity/UnityWrapper;",
    name = "KongregateAPIMtxHasItem",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/kongregate/android/api/KongregateAPI;",
            name = "mtx"
        ),
        methodCall(
            definingClass = "Lcom/kongregate/android/api/MicrotransactionServices;",
            name = "hasItem"
        )
    )
)

/**
 * ReceiptVerificationStatus.valueOf(String) — the stable, non-obfuscated enum
 * entry point used by the Kongregate receipt store (o/i/a.b(String), which
 * parses the "result" field of the kongregate_purchases SharedPreferences
 * JSON via valueOf) to map a stored result string to VALID / INVALID /
 * PROCESSING / UNKNOWN (smali-verified enum constants).
 *
 * Forcing valueOf → VALID makes every stored receipt verification report
 * VALID without touching any obfuscated class. Exact stable identity
 * (defining class + name), so no instruction filters are needed.
 */
object ReceiptValueOfFingerprint : Fingerprint(
    definingClass = "Lcom/kongregate/android/api/MicrotransactionServices\$ReceiptVerificationStatus;",
    name = "valueOf",
    returnType = "Lcom/kongregate/android/api/MicrotransactionServices\$ReceiptVerificationStatus;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;")
)

/**
 * Google receipt response-signature check (smali-verified in
 * classes3/com/kongregate/o/m/d.smali):
 *
 *   .method public static a(Ljava/lang/String;Ljava/lang/String;)Z
 *     Base64.decode(signature) → X509EncodedKeySpec → KeyFactory "RSA" …
 *
 * Called over appId+valid+orderId; defeating it lets forged/empty receipts
 * validate. Fingerprint anchors on Base64.decode + the "RSA" KeyFactory
 * string (in smali order). The defining class (o/m/d) is obfuscated and
 * deliberately NOT referenced.
 */
object GoogleResponseSigFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/util/Base64;",
            name = "decode"
        ),
        string("RSA")
    )
)

/**
 * Google Play Billing launch entry (smali-verified in
 * classes/com/android/billingclient/api/BillingClientImpl.smali):
 *
 *   .method public launchBillingFlow(Landroid/app/Activity;
 *     Lcom/android/billingclient/api/BillingFlowParams;)
 *     Lcom/android/billingclient/api/BillingResult;
 *
 * This is the ONLY entry Unity IAP uses to open the Play purchase sheet:
 * the JNI bridge launchBillingFlowCpp() delegates straight to it, and the
 * zzce testing-override subclass routes through it via zzbz.call() → zzal →
 * invoke-super — so patching this one method covers both paths.
 *
 * Anchors are stable Play Billing SDK calls in exact smali order (listener
 * lookup → BillingFlowParams SKU-list accessors → SKU extractors). NOTE:
 * the target body contains NO getResponseCode / self launchBillingFlow
 * calls (those live in the launchBillingFlowCpp bridge method), so the
 * filters anchor on the in-body sequence instead. The defining class
 * (BillingClientImpl) is the version-pinned SDK class, and no obfuscated
 * Kongregate names are referenced.
 */
object LaunchBillingFlowFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/app/Activity;", "Lcom/android/billingclient/api/BillingFlowParams;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/zzs;",
            name = "zzd"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "zzj"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams;",
            name = "zzk"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/SkuDetails;",
            name = "getSku"
        ),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/ProductDetails;",
            name = "getProductId"
        )
    )
)

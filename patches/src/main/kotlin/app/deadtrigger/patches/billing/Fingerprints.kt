package app.deadtrigger.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/*
 * Dead Trigger 2.3.4 (com.madfingergames.deadtrigger) — IAP fingerprints.
 *
 * All targets live in `com.madfingergames.billing.*`, the thin Java BillingClient bridge the
 * Unity IL2CPP layer calls over JNI (C# hardcodes the class/method strings, so these names are
 * JNI-bound and cannot be obfuscated). Every fingerprint below was re-verified instruction by
 * instruction against `analysis/deadtrigger/smali/` before writing — filter ORDER is the exact
 * order the matching `invoke-*` instructions appear in the smali file.
 *
 * Filter verification table (smali line numbers, classes6 unless noted):
 *
 * | Target | Method | .registers | Filters (in smali order)                 |
 * |--------|--------|-----------|-------------------------------------------|
 * | T1     | requestPurchase          | 4 | $4.<init>:251 → startCommand:253   |
 * | T1b    | $4$1.onProductDetailsResponse | 6 | setProductDetails:103 → launchBillingFlow:131 (classes6/BillingManager$4$1) |
 * | T2     | consumePurchase          | 3 | $5.<init>:78  → startCommand:80    |
 * | T3     | queryPurchases           | 3 | $6.<init>:184 → startCommand:186   |
 * | T4     | connect                  | 3 | $2.<init>:55  → runOnUiThread:57   |
 * | T5     | querySkuDetails          | 4 | $3.<init>:209 → startCommand:211   |
 * | T6     | isGoogleStoreInstalled   | 2 | const-string "com.android.vending":95 |
 */

/**
 * T1 (PRIMARY) — `BillingManager.requestPurchase(String sku, String skuType, UnityCallback)V`.
 *
 * Single entry point for every store buy tap from C#. Body is replaced by the patch with a
 * fabricated `Purchase` + direct `UnityCallback.onResult(0, purchase)`, so Play payment is never
 * launched.
 *
 * Confirmed smali: classes6/com/madfingergames/billing/BillingManager.smali:216 (.registers 4 —
 * static, so 1 local `v0` + p0=sku, p1=skuType, p2=callback). Filter order verified:
 * `invoke-direct {…}, BillingManager$4;-><init>` (line 251) precedes
 * `invoke-static {…}, BillingManager;->startCommand` (line 253).
 */
object RequestPurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager;",
    name = "requestPurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Lcom/madfingergames/billing/UnityCallback;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager\$4;", name = "<init>"),
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager;", name = "startCommand"),
    ),
)

/**
 * T1b (async fallback choke) — `BillingManager$4$1.onProductDetailsResponse(BillingResult, List)V`.
 *
 * Anonymous `ProductDetailsResponseListener` created only inside `BillingManager$4.run()`, i.e.
 * only on the real purchase path. It is the ONLY place in the whole app that calls
 * `BillingClient.launchBillingFlow` (rg over smali/ confirms a single call site at
 * BillingManager$4$1.smali:131) — so replacing its body guarantees the Play payment UI can never
 * open, even if some path we have not seen constructs the listener.
 *
 * With T1 active this listener is never instantiated (T1 never creates `BillingManager$4`), so
 * this is dead-but-defensive code; it is also the ready-made *async* delivery path (it runs on
 * the UI thread) should the synchronous T1 callback ever prove to misbehave from within the JNI
 * frame — in that case T1's body patch would be reverted to keep the original `$4` handoff and
 * this target becomes the active deliverer.
 *
 * Confirmed smali: classes6/com/madfingergames/billing/BillingManager$4$1.smali:46
 * (.registers 6 — instance, p0=this, p1=BillingResult, p2=List, locals v0-v2). Filter order
 * verified: `setProductDetails` (line 103) precedes `launchBillingFlow` (line 131).
 */
object ProductDetailsResponseFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager\$4\$1;",
    name = "onProductDetailsResponse",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/android/billingclient/api/BillingResult;", "Ljava/util/List;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/android/billingclient/api/BillingFlowParams\$ProductDetailsParams\$Builder;",
            name = "setProductDetails",
        ),
        methodCall(definingClass = "Lcom/android/billingclient/api/BillingClient;", name = "launchBillingFlow"),
    ),
)

/**
 * T2 — `BillingManager.consumePurchase(String purchaseToken, UnityCallback)V`.
 *
 * C# consumes consumables right after a grant; `BillingClient.consumeAsync` rejects the fake
 * token (Play error 5) and the grant is dropped — the exact failure mode proven on device for
 * Into the Dead 2. Body is replaced with a direct `onResult(0, null)`, which is byte-for-byte
 * what the real `BillingManager$5$1.onConsumeResponse` forwards (response code, null data).
 *
 * Confirmed smali: BillingManager.smali:62 (.registers 3 — static, 1 local `v0`,
 * p0=token, p1=callback). Filter order verified: `BillingManager$5;-><init>` (line 78) precedes
 * `startCommand` (line 80).
 */
object ConsumePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager;",
    name = "consumePurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "Lcom/madfingergames/billing/UnityCallback;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager\$5;", name = "<init>"),
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager;", name = "startCommand"),
    ),
)

/**
 * T3 — `BillingManager.queryPurchases(String skuType, UnityCallback)V`.
 *
 * C# restores owned items at startup and checks subscription (`Weekly`) ownership. A sideloaded
 * build gets `[]` back from Play, so the subscription state is lost. Body is replaced with a
 * fabricated `ArrayList` holding a `Weekly` purchase.
 *
 * Confirmed smali: BillingManager.smali:168 (.registers 3 — static, 1 local `v0`,
 * p0=skuType, p1=callback). Filter order verified: `BillingManager$6;-><init>` (line 184)
 * precedes `startCommand` (line 186).
 */
object QueryPurchasesFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager;",
    name = "queryPurchases",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;", "Lcom/madfingergames/billing/UnityCallback;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager\$6;", name = "<init>"),
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager;", name = "startCommand"),
    ),
)

/**
 * T4 — `BillingManager.connect(UnityCallback)V`.
 *
 * `startConnection` on a device without a working Play Store reports an error and C# then
 * disables the whole store (`IAPServiceAvailable` gate). Body is replaced with
 * `onResult(0, null)` — exactly what the real `BillingManager$2$1.onBillingSetupFinished`
 * forwards on success (response code from BillingResult, null data).
 *
 * Confirmed smali: BillingManager.smali:39 (.registers 3 — static, locals v0/v1, p0=callback).
 *
 * FILTER ORDER CORRECTED vs notes/premium-bypass.md: the notes list
 * `Activity.runOnUiThread` **before** `BillingManager$2.<init>`, but the actual instruction
 * order is `new-instance $2` (53) → `invoke-direct $2.<init>` (55) →
 * `invoke-virtual Activity.runOnUiThread` (57). The filter below follows the smali, not the notes.
 */
object ConnectFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager;",
    name = "connect",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC),
    parameters = listOf("Lcom/madfingergames/billing/UnityCallback;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager\$2;", name = "<init>"),
        methodCall(definingClass = "Landroid/app/Activity;", name = "runOnUiThread"),
    ),
)

/**
 * T5 — `BillingManager.querySkuDetails(String[] skus, String skuType, UnityCallback)V`.
 *
 * Not optional in practice: this is the only `startCommand` caller the patch does not otherwise
 * neutralise, and `startCommand`'s "not ready" path wraps the request in `BillingManager$7` and
 * hands it to `connect(...)`. Patched `connect` answers 0 (OK), which makes `$7.onResult` take
 * the success branch and run `BillingManager$3.run()` — whose `invoke-virtual
 * {v1,…}, BillingClient;->querySkuDetailsAsync` dereferences the still-NULL `mBillingClient`
 * (it is only assigned in `BillingManager$2.run()`, which our patched `connect` never reaches)
 * → NPE. Patching this entry point keeps `startCommand` permanently unreachable (its four
 * callers are T1/T2/T3/T5, all patched) and doubles as the fake price catalog that keeps the
 * store UI usable without Play.
 *
 * Confirmed smali: BillingManager.smali:191 (.registers 4 — static, 1 local `v0`,
 * p0=skus, p1=skuType, p2=callback). Filter order verified: `BillingManager$3;-><init>`
 * (line 209) precedes `startCommand` (line 211).
 */
object QuerySkuDetailsFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager;",
    name = "querySkuDetails",
    returnType = "V",
    accessFlags = listOf(AccessFlags.STATIC),
    parameters = listOf("[Ljava/lang/String;", "Ljava/lang/String;", "Lcom/madfingergames/billing/UnityCallback;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager\$3;", name = "<init>"),
        methodCall(definingClass = "Lcom/madfingergames/billing/BillingManager;", name = "startCommand"),
    ),
)

/**
 * T6 — `BillingManager.isGoogleStoreInstalled()Z`.
 *
 * Reports whether the `com.android.vending` package is resolvable. C# uses it as an availability
 * gate for the whole shop; on non-Play devices it returns false and the store never opens. The
 * patch forces `true` so the (fully faked) purchase pipeline is always reachable. Harmless on
 * Play-equipped devices, where it already returns true.
 *
 * Confirmed smali: BillingManager.smali:85 (`.method public static isGoogleStoreInstalled()Z`,
 * .registers 2 — no parameters). Access flags verified as PUBLIC|STATIC; the stable filter is the
 * package-name literal at line 95.
 */
object IsGoogleStoreInstalledFingerprint : Fingerprint(
    definingClass = "Lcom/madfingergames/billing/BillingManager;",
    name = "isGoogleStoreInstalled",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("com.android.vending"),
    ),
)

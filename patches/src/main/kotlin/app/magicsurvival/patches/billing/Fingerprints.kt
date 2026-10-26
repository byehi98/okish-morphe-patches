package app.magicsurvival.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play Billing 8.3.0 (com.android.billingclient.api, classes.dex) ────
//
// Magic Survival is Unity 2022.3 IL2CPP: the game logic lives in libil2cpp.so
// and the C# side drives billing directly over JNI (AndroidJavaObject) against
// the real billing classes — there is no Unity IAP Java glue in the DEX and no
// Java caller of launchBillingFlow anywhere outside the library itself.
// The runtime client instance is `zzcw` (returned by BillingClient.newBuilder),
// which extends BillingClientImpl and wraps launchBillingFlow / consumeAsync /
// acknowledgePurchase around super — so patching BillingClientImpl covers the
// public entry, while `zzcw`'s two async wrappers are patched separately to keep
// our fabricated callbacks on the calling (Unity) thread.

// BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams): BillingResult
// The single funnel every purchase goes through. `zzcw.launchBillingFlow` builds
// a Callable that invokes super inline on the caller's thread (zzcw.java:249),
// so an injection at index 0 runs on the Unity main thread.
// Smali: classes/com/android/billingclient/api/BillingClientImpl.smali
// (.registers 30 → v0..v26 free, p0=this, p1=Activity, p2=BillingFlowParams).
object BillingLaunchBillingFlowFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    parameters = listOf("Landroid/app/Activity;", "Lcom/android/billingclient/api/BillingFlowParams;"),
)

// BillingClientImpl.queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener): void
// NOT overridden by zzcw, so it runs inline on the caller's thread — this is the
// restore path Unity IAP uses on every startup to re-grant non-consumables
// (remove_ad). We rebuild the answer from our own persisted grant set.
// Smali (.registers 10 → v0..v6 locals, p0=p1=p2 reserved).
object BillingQueryPurchasesAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "queryPurchasesAsync",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/QueryPurchasesParams;",
        "Lcom/android/billingclient/api/PurchasesResponseListener;",
    ),
)

// BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener): void
// Drops a granted id from the persisted set (so consumables are not re-granted
// on every launch) and reports success.
// Smali (.registers 9 → v0..v5 locals).
object BillingConsumeAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "consumeAsync",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;",
    ),
)

// BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams, AcknowledgePurchaseResponseListener): void
// Acknowledgement of a purchase that Google never heard about would always fail
// against the real service, so the listener is answered OK directly.
// Smali (.registers 9 → v0..v5 locals).
object BillingAcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "acknowledgePurchase",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;",
    ),
)

// zzcw.consumeAsync(...) — the runtime subclass wraps the real call in
// zzaP(), which executes it on the client's ExecutorService (zzcw.java:225).
// That would deliver our fabricated callback on a background thread, i.e. into
// the C# AndroidJavaProxy off the Unity main thread. Re-routing it straight to
// super keeps the callback on the thread C# called from.
// Smali (.registers 5 → v0, v1 locals + p0, p1, p2).
object ZzcwConsumeAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/zzcw;",
    name = "consumeAsync",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;",
    ),
)

// zzcw.acknowledgePurchase(...) — same zzaP() executor wrapper (zzcw.java:208).
// Smali (.registers 5 → v0, v1 locals + p0, p1, p2).
object ZzcwAcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/zzcw;",
    name = "acknowledgePurchase",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;",
    ),
)

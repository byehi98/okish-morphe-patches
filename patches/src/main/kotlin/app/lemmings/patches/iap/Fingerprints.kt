package app.lemmings.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Lemmings: Puzzle Survival 9.4.4 (versionCode 7680) — Unity IL2CPP ────────
//
// The game has NO Java game logic: the store funnel lives in libil2cpp.so and
// the Java layer is only SDK bridges. The previous revision of this patch
// hooked `com.unity.purchasing.common.UnityPurchasing.OnPurchaseFailed` —
// smali proves that method is DEAD CODE in this build:
//
//   * `rg UnityPurchasing` over all of analysis/.../smali/classes7 matches ONLY
//     UnityPurchasing.smali itself — zero `new-instance`, zero call sites.
//   * Nobody constructs `PurchaseFailureDescription`, nobody implements
//     `IUnityCallback` / `IStoreCallback` in DEX (the concrete Java store,
//     e.g. com.unity.purchasing.googleplay.*, was stripped; only the
//     interfaces + data classes + abstract StoreDeserializer ship).
//   * The live failure string `onPurchaseFailedEvent(...)` appears NOWHERE in
//     smali — it is a native C# log. The real failure path is 100% native:
//     C# (IL2CPP Unity IAP) → JNI → billingclient → Play → broadcast →
//     `zzaa.onReceive` → C# PurchasesUpdatedListener proxy (AndroidJavaProxy)
//     → `PurchasingManager:OnPurchaseFailed`. Java `UnityPurchasing` is never
//     on this path, so the old hook could never fire. That is the root cause.
//
// The replacement hook below targets the ONE stable, smali-verified DEX choke
// point that every failed Play-sheet checkout passes through with the failure
// still convertible: `ProxyBillingActivity.onActivityResult` (billingclient
// 8.3.0, `com.android.billingclient:billing@@8.3.0` per the .source tag).
//
// Verified chain (all smali line refs are the shipped files):
//   ProxyBillingActivity.onActivityResult  (classes2/.../api/ProxyBillingActivity.smali)
//     → builds Intent action LOCAL_BROADCAST_PURCHASES_UPDATED + sendBroadcast
//   zzaa.onReceive  (classes2/.../api/zzaa.smali, BillingBroadcastManager)
//     → cond_bc: BillingResult from intent extras (RESPONSE_CODE)
//     → goto_249: purchase list via `zzc.zzm(bundle)` (INAPP_PURCHASE_DATA_LIST
//       + INAPP_DATA_SIGNATURE_LIST, each pair → `new Purchase(json, sig)`;
//       Purchase.<init> only requires parseable JSON, no signature check)
//     → `PurchasesUpdatedListener.onPurchasesUpdated(result, purchases)`
//       → C# proxy → success path → ProcessPurchase → grant.
//
// Stability: every identifier in the fingerprint AND in the injected code is
// public billingclient / framework API — zero obfuscated `zz*` references.
// The billingclient version is pinned by the app (8.3.0), and the patch is
// further pinned to app 9.4.4 via COMPATIBILITY_LEMMINGS.

/**
 * `ProxyBillingActivity.onCreate(Landroid/os/Bundle;)V` — the Play-sheet launcher.
 * `BillingClientImpl.launchBillingFlow` (BillingClientImpl.smali:11593-11620)
 * starts this activity with a `BUY_INTENT` PendingIntent extra; `onCreate`
 * then calls `startIntentSenderForResult` (ProxyBillingActivity.smali:868-891),
 * which is what opens the Google Play sheet the user sees.
 *
 * Ordered filters mirror the shipped instruction order: the `super.onCreate`
 * call (first instruction, ~line 687) precedes the launch log string (~line
 * 705) which precedes the `BUY_INTENT` extra check (~line 717) which precedes
 * the converging `sendBroadcast` (~line 937). `BUY_INTENT` also occurs in
 * `BillingClientImpl`, but the definingClass + name + signature pin below
 * selects this method exactly; in-app-message flows (`IN_APP_MESSAGE_INTENT`,
 * requestCode 0x65) carry no `BUY_INTENT` and are never intercepted.
 */
object ProxyBillingCreateFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/ProxyBillingActivity;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/app/Activity;",
            name = "onCreate",
        ),
        string("Launching Play Store billing flow"),
        string("BUY_INTENT"),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/ProxyBillingActivity;",
            name = "sendBroadcast",
        ),
    )
)

/**
 * `ProxyBillingActivity.onActivityResult(IILandroid/content/Intent;)V` — the
 * Play-sheet result handler. Every tapped store item opens this activity
 * (observed live: `ProxyBillingActivity + SheetUiBuilderHostActivity`), and
 * every dismiss/fail returns here (observed live: `resultCode 3,
 * billing's responseCode: 5` = DeveloperError on the re-signed APK).
 *
 * Ordered filters mirror the shipped instruction order: the resultCode log
 * string (~line 509) precedes the INTENT_SOURCE extras (~line 613+) which
 * precede the converging `sendBroadcast` (~line 671).
 */
object ProxyBillingResultFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/ProxyBillingActivity;",
    name = "onActivityResult",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("I", "I", "Landroid/content/Intent;"),
    filters = listOf(
        string("Activity finished with resultCode "),
        string("INTENT_SOURCE"),
        methodCall(
            definingClass = "Lcom/android/billingclient/api/ProxyBillingActivity;",
            name = "sendBroadcast",
        ),
    )
)

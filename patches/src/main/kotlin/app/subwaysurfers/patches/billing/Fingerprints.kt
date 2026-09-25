package app.subwaysurfers.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.android.billingclient.api.ProxyBillingActivity.onCreate(Bundle) — protected.
 *
 * The Play Billing 8.0.0 trampoline activity that shows the Google Play
 * purchase popup. The launchBillingFlow BUY path in BillingClientImpl builds
 * an Intent for this class with the Play "BUY_INTENT" PendingIntent extra and
 * starts it with a plain startActivity (BillingClientImpl.smali:16316
 * const-class, :16391 startActivity — NOT for-result), so the purchase outcome
 * returns to the client only through the LOCAL_BROADCAST_PURCHASES_UPDATED
 * broadcast this activity sends from onActivityResult / onDestroy.
 *
 * v4 (see NoPlayUiPatch): body-replacing onCreate now FABRICATES the success
 * broadcast the onActivityResult path would have sent (ProxyBillingActivity
 * .smali:781 makePurchasesUpdatedIntent → :789 putExtras → :868 sendBroadcast)
 * and finishes — no Play UI, no cancel, the C# side receives a genuine
 * OK + [Purchase] and runs its natural success flow.
 *
 * Confirmed smali: classes2/com/android/billingclient/api/
 * ProxyBillingActivity.smali:876 (method), :877 (.registers 14; p0=this,
 * p1=Bundle, locals v0-v11), super call :886, startIntentSenderForResult
 * :1251 (made unreachable). Note ProxyBillingActivityV2 also exists in this
 * APK, but the BUY path above references ProxyBillingActivity only.
 *
 * Filters are in exact instruction order (verified against smali):
 *   "Launching Play Store billing flow" (fresh-launch path, :923)
 *   → Activity.startIntentSenderForResult (the Play popup launch, :1251)
 *   → "Launching Play Store billing flow from savedInstanceState"
 *     (restore path, :1344)
 */
object ProxyBillingActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/ProxyBillingActivity;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        string("Launching Play Store billing flow"),
        methodCall(definingClass = "Landroid/app/Activity;", name = "startIntentSenderForResult"),
        string("Launching Play Store billing flow from savedInstanceState"),
    )
)

/**
 * BillingClientImpl.launchBillingFlow(Activity, BillingFlowParams)BillingResult
 * (classes2/com/android/billingclient/api/BillingClientImpl.smali:13071,
 * .registers 31 — p0=this=v28, p1=Activity=v29, p2=BillingFlowParams=v30).
 *
 * The BUY path at :cond_619 (:16311) builds the ProxyBillingActivity launch
 * Intent and — because the app-side intent carries no product id — we inject a
 * 3-instruction straight-line tag right before the startActivity at :16391
 * (inside try {:try_start_63b .. :try_end_641}) so ProxyBillingActivity can
 * read back WHICH product was tapped via the "morphe.productId" extra.
 * See NoPlayUiPatch for the register-liveness proof.
 *
 * Public API method, unique by (name, returnType, parameters) within the
 * class; ordered body filters additionally pin the exact BUY path:
 *   string "BUY_INTENT" (:1571/:1579)
 *   → string "wasServiceAutoReconnected" (:1591)
 *   → Activity.startActivity (:16391)
 * (exact smali order verified).
 */
object BillingClientImplLaunchBillingFlowFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "launchBillingFlow",
    returnType = "Lcom/android/billingclient/api/BillingResult;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/app/Activity;", "Lcom/android/billingclient/api/BillingFlowParams;"),
    filters = listOf(
        string("BUY_INTENT"),
        string("wasServiceAutoReconnected"),
        methodCall(definingClass = "Landroid/app/Activity;", name = "startActivity"),
    )
)

/**
 * BillingClientImpl.acknowledgePurchase(AcknowledgePurchaseParams,
 * AcknowledgePurchaseResponseListener)V
 * (classes2/.../BillingClientImpl.smali:10925, .registers 9 —
 * p0=this=v6, p1=params=v7, p2=listener=v8, locals v0-v5).
 *
 * v4 short-circuits it: the fabricated purchase is never registered with
 * Play, so a real acknowledge would fail over the network. We answer OK
 * straight to the listener instead (straight-line, index 0, no labels →
 * safe against the exception-table label-drop).
 *
 * Public API method, unique by (name, returnType, parameters) → no body
 * filters required (same rationale as the Missiles/Traffic Rider patches on
 * identical billing@@8.0.0).
 */
object BillingClientImplAcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "acknowledgePurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/AcknowledgePurchaseParams;",
        "Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;"
    )
)

/**
 * BillingClientImpl.consumeAsync(ConsumeParams, ConsumeResponseListener)V
 * (classes2/.../BillingClientImpl.smali:11008, .registers 9 —
 * p0=this=v6, p1=params=v7, p2=listener=v8, locals v0-v5).
 *
 * v4 short-circuits it: coin packs are consumables, so C# re-consumes after
 * every grant — against a fake token the real path would fail and could make
 * the C# flow treat the (already granted) purchase as failed. We echo the
 * token back with an OK BillingResult immediately.
 *
 * Public API method, unique by (name, returnType, parameters) → no body
 * filters required.
 */
object BillingClientImplConsumeAsyncFingerprint : Fingerprint(
    definingClass = "Lcom/android/billingclient/api/BillingClientImpl;",
    name = "consumeAsync",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/android/billingclient/api/ConsumeParams;",
        "Lcom/android/billingclient/api/ConsumeResponseListener;"
    )
)

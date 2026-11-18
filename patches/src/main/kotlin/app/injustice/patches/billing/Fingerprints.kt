package app.injustice.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * macrotransaction.google.MicroTransactionAndroid.IsAllowedToMakePurchased()Z — public.
 *
 * The store-availability gate: MicroTransaction.IsAvailable() (driving the native store
 * UI) delegates here, and this wrapper hardcodes `false` instead of consulting
 * GooglePlayStoreHelper's bIsIapSetup. That false is what keeps the in-game store
 * closed/unavailable.
 *
 * Confirmed smali: classes5.dex macrotransaction/google/MicroTransactionAndroid.smali:2016
 * (.registers 2) — the entire body is exactly two instructions:
 *
 *   const/4 v0, 0x0
 *   return v0
 *
 * Filters are in exact instruction order (verified against smali): the hardcoded
 * literal 0, then the RETURN. The method is uniquely identified by its non-obfuscated
 * name + signature; the filters pin the body shape.
 */
object StoreAvailabilityFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/google/MicroTransactionAndroid;",
    name = "IsAllowedToMakePurchased",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        literal(0),
        opcode(Opcode.RETURN)
    )
)

/**
 * macrotransaction.ReceiptValidateService_.VerificationResultAdapter.getResult(
 * EServerErrorCode, String) — public static.
 *
 * The single funnel for ALL server receipt-validation outcomes (server OK / fake /
 * offline / unreachable / other). Native (libInjusticeGAU.so) trusts the returned
 * PlatformInterfaceDelegateResult: purchase success is signaled by
 * Successful=true + Data.IntValue=MTR_Succeeded(0) + Data.IntValue2=ReceiptValidation_OK(0).
 *
 * Confirmed smali: classes5.dex
 * macrotransaction/ReceiptValidateService_$VerificationResultAdapter.smali:67
 * (.registers 6, `public static getResult(...)`). The method:
 *
 *   new-instance v0, PlatformInterfaceDelegateResult
 *   const/4 v1, 0x0                          <- <init>(Z) arg: Successful=false default
 *   invoke-direct {v0, v1}, <init>(Z)V
 *   ...sets Data.Type=PIDT_Custom, Data.IntValue=MTR_Failed...
 *   invoke-virtual {p0}, EServerErrorCode->ordinal()I
 *   move-result v1
 *   const/4 v2, 0x1
 *   if-eqz v1, :cond_3f     <- ordinal 0 (ESE_Success, verified via enum $values())
 *                              branches to the ONLY branch that sets
 *                              MTR_Succeeded + ReceiptValidation_OK + Successful=true
 *
 * Filters are in exact instruction order (verified against smali): the `new-instance`
 * of PlatformInterfaceDelegateResult, the `const/4 v1, 0x0` constructor argument, and
 * the `<init>(Z)` invoke. The non-obfuscated class + name + signature pin the method;
 * these three pin the "Successful=false then ordinal-branch" body shape.
 */
object ReceiptVerificationFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/ReceiptValidateService_\$VerificationResultAdapter;",
    name = "getResult",
    returnType = "Lcom/epicgames/virtuos/UnrealEngine3/components/PlatformInterfaceDelegateResult;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Lmacrotransaction/ReceiptValidateService_\$EServerErrorCode;",
        "Ljava/lang/String;"
    ),
    filters = listOf(
        opcode(Opcode.NEW_INSTANCE),
        literal(0),
        methodCall(
            definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/components/PlatformInterfaceDelegateResult;",
            name = "<init>",
        ),
    )
)

/**
 * macrotransaction.MicrotransactionPolicy.GetInvalidReceiptPolicy() — public static.
 *
 * Returns the policy applied when a receipt fails server validation (fake/invalid).
 * Default is Policy.Allow (grant anyway), but the body then consults remote Swrve
 * A/B config (SwrveResourceHelper.getABTestValue("MicroTransactionPolicy",
 * "ReceiptFailurePurchasePolicy")) and can flip the result to Policy.Reject via
 * Policy.fromValue().
 *
 * Confirmed smali: classes5.dex macrotransaction/MicrotransactionPolicy.smali:24
 * (.registers 3):
 *   sget-object v0, Policy->Allow
 *   const-string v1, "MicroTransactionPolicy"
 *   const-string v2, "ReceiptFailurePurchasePolicy"
 *   invoke-static {v1, v2}, SwrveResourceHelper->getABTestValue(...)Ljava/lang/String;
 *   ... if non-null → Integer.parseInt → Policy.fromValue → may become Reject
 *   return-object v0
 *
 * The "ReceiptFailurePurchasePolicy" A/B key is unique to this method (the sibling
 * GetInvalidResponsePolicy uses "ReceiptOfflinePurchasePolicy") and pins the body that
 * can override the Allow default.
 */
object InvalidReceiptPolicyFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/MicrotransactionPolicy;",
    name = "GetInvalidReceiptPolicy",
    returnType = "Lmacrotransaction/MicrotransactionPolicy\$Policy;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("ReceiptFailurePurchasePolicy")
    )
)

/**
 * macrotransaction.MicrotransactionPolicy.GetInvalidResponsePolicy() — public static.
 *
 * Returns the policy applied when the WB verification server is unreachable /
 * malformed (no valid response). Same shape as GetInvalidReceiptPolicy: defaults to
 * Policy.Allow but can be flipped to Policy.Reject by the remote Swrve A/B value keyed
 * "ReceiptOfflinePurchasePolicy".
 *
 * Confirmed smali: classes5.dex macrotransaction/MicrotransactionPolicy.smali:62
 * (.registers 3) — identical structure to GetInvalidReceiptPolicy with the
 * "ReceiptOfflinePurchasePolicy" key, which uniquely selects this method.
 */
object InvalidResponsePolicyFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/MicrotransactionPolicy;",
    name = "GetInvalidResponsePolicy",
    returnType = "Lmacrotransaction/MicrotransactionPolicy\$Policy;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("ReceiptOfflinePurchasePolicy")
    )
)

/**
 * macrotransaction.google.MicroTransactionAndroid.BeginPurchase(String)String — public.
 *
 * The SINGLE Java choke point every Google store tap must pass:
 * UE3JavaApp.JavaCallback_MicroTransactionBeginPurchase(String) → MicroTransaction.
 * BeginPurchase(String) → this method. It is the earliest point that (a) already holds
 * the tapped productId (p1) and (b) can call the instance method firePurchaseDone(...)
 * that queues the PlatformInterfaceDelegateResult native consumes.
 *
 * Confirmed smali: classes5.dex macrotransaction/google/MicroTransactionAndroid.smali:170
 * (.registers 7, public, non-static, single String→String overload). Body:
 *
 *   const-string v2, "start purchasing "                      ; line 181 (Log.d prefix)
 *   ...
 *   invoke-static {}, MicrotransactionPolicy->MicrotransactionEnabled()Z   ; line 196
 *   if-nez v1, :cond_2c
 *   iget-object v1, p0, ...->mHelper:GooglePlayStoreHelper
 *   invoke-virtual {p1}, String->toLowerCase()String
 *   invoke-virtual p0->getCurrentUser()String
 *   invoke-virtual {v1, v2, v3, v0}, GooglePlayStoreHelper->BeginPurchase(String,String,OnPurchaseFinishedListener)Z  ; line 213
 *   ...
 *   return-object p1                                        ; line 254
 *
 * Filters are in exact instruction order (verified against smali): the unique Log.d
 * prefix "start purchasing ", the MicrotransactionPolicy.MicrotransactionEnabled() gate
 * that decides between the Play path and the heartbeat path, and the
 * GooglePlayStoreHelper.BeginPurchase(...) call that is THE gateway to
 * queryProductDetailsAsync → launchBillingFlow (the Play dialog T7 prevents). The
 * non-obfuscated class + method + (String)String signature pin the method; there is
 * exactly one BeginPurchase overload in this class.
 *
 * Disjoint from T4's fingerprint (ReceiptValidateService_$VerificationResultAdapter.
 * getResult): T4 rewrites the factory body, T7 calls the factory — they compose, no
 * overlap, so morphe-cli applies both patches independently.
 */
object BeginPurchaseFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/google/MicroTransactionAndroid;",
    name = "BeginPurchase",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("start purchasing "),
        methodCall(
            definingClass = "Lmacrotransaction/MicrotransactionPolicy;",
            name = "MicrotransactionEnabled",
        ),
        methodCall(
            definingClass = "Lmacrotransaction/epicgames/ue4/util/GooglePlayStoreHelper;",
            name = "BeginPurchase",
        ),
    )
)

/**
 * macrotransaction.epicgames.ue4.util.GooglePlayStoreHelper$2.onBillingSetupFinished(
 * BillingResult)V — public (anonymous BillingClientStateListener).
 *
 * The Billing client connection gate. A re-signed APK is not the build registered in
 * Play, so the client answers DEVELOPER_ERROR / BILLING_UNAVAILABLE. In that case this
 * callback takes the failure branch: `bIsIapSetup` stays false and
 * `val$InSetupFinishedListener.onHelperSetupFinished()` is never called, so the native
 * store never receives `NativeCallback_MicroTransactionOnInit` — this is the state in
 * which the game reports that it cannot connect to Google Play.
 *
 * Confirmed smali: classes5.dex
 * macrotransaction/epicgames/ue4/util/GooglePlayStoreHelper$2.smali:96
 * (.locals 3). The success branch's unique log line pins the method; the listener
 * interface callback it invokes is the hook our bridge replaces.
 */
object BillingSetupStateListenerFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/epicgames/ue4/util/GooglePlayStoreHelper\$2;",
    name = "onBillingSetupFinished",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/android/billingclient/api/BillingResult;"),
    filters = listOf(
        string("GooglePlayStoreHelper In-app billing supported for ")
    )
)

/**
 * macrotransaction.google.MicroTransactionAndroid.Init(Activity)V — public.
 *
 * Where the Java store backend is created. Native `UMicroTransactionAndroid::Init`
 * (libInjusticeGAU.so 0x27cf948) sets field +0xc4 = 1 ("purchases blocked") and then
 * calls Java `MicroTransaction.Init()`; the ONLY thing that clears +0xc4 is
 * `NativeCallback_MicroTransactionOnInit`, which native dispatches when Java calls it.
 * Upstream that call is queued by `HelperSetupFinishedListener.onHelperSetupFinished()`
 * after a successful Play Billing connection — a callback that never arrives on devices
 * where the billing service cannot bind, leaving the storefront with the
 * "Cannot Connect To Google Play" banner and dead GET buttons.
 *
 * Hooking the entry of this method lets the bridge queue the app's own
 * `SetupFinishedCommand` unconditionally, so OnInit is always delivered through
 * `MicroTransactionAndroid.update()` on the game thread.
 *
 * Confirmed smali: classes5.dex macrotransaction/google/MicroTransactionAndroid.smali:222
 * (.locals 12). The unique SKU literal from `skuNameTable` and the
 * `GooglePlayStoreHelper.<init>` call pin the body.
 */
object StoreInitFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/google/MicroTransactionAndroid;",
    name = "Init",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/app/Activity;"),
    filters = listOf(
        string("com.wb.injustice.brawler2013.powercredits_1"),
        methodCall(
            definingClass = "Lmacrotransaction/epicgames/ue4/util/GooglePlayStoreHelper;",
            name = "<init>",
        ),
    )
)

/**
 * macrotransaction.google.MicroTransactionAndroid.refreshProductList(String[])V — public.
 *
 * The storefront catalog request (`JavaCallback_MicroTransactionGetAvailableProducts` →
 * `MicroTransaction.GetAvailableProducts` → `IMicroTransaction.refreshProductList`).
 * Stock body lowercases each native SKU, maps it through `getSafeString`, then queries
 * Play through `GooglePlayStoreHelper.queryInventoryAsync`. When that query fails,
 * `GotInventoryListener` queues `ProductListCommand(skus, null)` and the command calls
 * `NativeCallback_MicroTransactionOnGetAvailableProductsFailed`, which is what leaves
 * the native store without any buyable items.
 *
 * Confirmed smali: classes5.dex macrotransaction/google/MicroTransactionAndroid.smali:2616
 * (.locals 3). The method is uniquely identified by class + name + `([Ljava/lang/String;)V`
 * signature; the post to `UE3JavaApp.GetHandler()` pins the body shape.
 */
object ProductListRefreshFingerprint : Fingerprint(
    definingClass = "Lmacrotransaction/google/MicroTransactionAndroid;",
    name = "refreshProductList",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("[Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;",
            name = "GetHandler",
        )
    )
)

/**
 * com.epicgames.virtuos.UnrealEngine3.UE3JavaApp.JavaCallback_IsNetConnected()Z — public.
 *
 * The Java end of the engine's network predicate:
 *
 *   UEngine::HasNetworkConnection()        (libInjusticeGAU.so 0x2392d4c)
 *     -> CallJava_IsNetConnected()         (0x1977a68, JNI CallBooleanMethod)
 *       -> UE3JavaApp.JavaCallback_IsNetConnected()
 *
 * This is the **first** check in UnrealScript `InjusticeIOSGame.HandlePurchase` (export
 * 8027): the popup branch at script offset 0x20 calls `EX_FinalFunction`
 * `HasNetworkConnection` (import 348), and when it returns false the handler runs
 * `DisplayCouldNotConnect(Localize("PopupMessages", "CouldNotConnectToItunes",
 * "InjusticeIOSGame"))` and returns before `BeginPurchase` is ever reached. That is the
 * "Cannot Connect To Google Play" dialog seen on every GET tap.
 *
 * Confirmed smali: classes.dex
 * com/epicgames/virtuos/UnrealEngine3/UE3JavaApp.smali:6779 (.locals 1):
 *
 *   iget-boolean v0, p0, ->isConnecting:Z
 *   if-eqz v0, :cond_0
 *   const/4 v0, 0x1
 *   return v0
 *   :cond_0
 *   iget-boolean v0, p0, ->isConnected:Z
 *   return v0
 *
 * `isConnected` is only set by the app's WB network checker; on a re-signed/emulated
 * build it stays false even while the device is online, so every purchase is rejected
 * by the script. The method name + signature are unique and the `IGET_BOOLEAN` filter
 * pins the body shape.
 */
object NetConnectedFingerprint : Fingerprint(
    definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;",
    name = "JavaCallback_IsNetConnected",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.IGET_BOOLEAN)
    )
)
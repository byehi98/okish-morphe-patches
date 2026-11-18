package app.injustice.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Smali class descriptors containing '$' (inner-class/enum separator). The \$ escapes
// keep Kotlin from treating $ as template interpolation; the values are interpolated
// verbatim into the injected smali below. All verified against classes5.dex:
//   MicrotransactionPolicy.Policy.Allow (public static final enum field,
//     MicrotransactionPolicy.smali:24-29)
//   ReceiptValidateService_$EServerErrorCode.ESE_Success (public static final enum field,
//     ordinal 0 — first entry of $values(), ReceiptValidateService_$EServerErrorCode.smali:65)
//   ReceiptValidateService_$VerificationResultAdapter.getResult(EServerErrorCode, String)
//     (public static, ReceiptValidateService_$VerificationResultAdapter.smali:67)
//   MicroTransactionAndroid.firePurchaseDone(PlatformInterfaceDelegateResult)V
//     (public, MicroTransactionAndroid.smali:2032)
private const val POLICY_ALLOW_REF = "Lmacrotransaction/MicrotransactionPolicy\$Policy;->Allow:Lmacrotransaction/MicrotransactionPolicy\$Policy;"
private const val ESE_SUCCESS_REF = "Lmacrotransaction/ReceiptValidateService_\$EServerErrorCode;->ESE_Success:Lmacrotransaction/ReceiptValidateService_\$EServerErrorCode;"
private const val GET_RESULT_REF = "Lmacrotransaction/ReceiptValidateService_\$VerificationResultAdapter;->getResult(Lmacrotransaction/ReceiptValidateService_\$EServerErrorCode;Ljava/lang/String;)Lcom/epicgames/virtuos/UnrealEngine3/components/PlatformInterfaceDelegateResult;"
private const val FIRE_PURCHASE_DONE_REF = "Lmacrotransaction/google/MicroTransactionAndroid;->firePurchaseDone(Lcom/epicgames/virtuos/UnrealEngine3/components/PlatformInterfaceDelegateResult;)V"
private const val STORE_BRIDGE = "Lapp/morphe/extension/injustice/StoreBridge;"

/**
 * Injustice: Gods Among Us — Billing Bypass (consolidated)
 *
 * Single patch covering every billing gate for com.wb.goog.injustice 3.5.1. Each
 * fingerprint.method is resolved independently inside the execute block, so the edits
 * compose without overlap (T4 rewrites the result factory's body, T7 calls the factory
 * at the success invariant; T3 keeps the store reachable; T6 pins the failure/offline
 * grant policy; T11 opens the script's network gate). No shared registers across edits —
 * every injected block is register-safe within its own method exactly as before.
 *
 * T3 — Store Availability
 *   macrotransaction.google.MicroTransactionAndroid.IsAllowedToMakePurchased()Z is
 *   hardcoded false (classes5.dex .../MicroTransactionAndroid.smali:2016,
 *   .registers 2 / const/4 v0, 0x0 / return v0); MicroTransaction.IsAvailable()
 *   delegates here, so the false closes the native store UI. Swap 0x0 → 0x1.
 *
 * T4 — Receipt Validation Bypass
 *   macrotransaction.ReceiptValidateService_.VerificationResultAdapter.getResult(...)
 *   is the single funnel for EVERY send-to-server receipt outcome. It builds
 *   `new PlatformInterfaceDelegateResult(false)` and only sets Successful=true +
 *   MTR_Succeeded + ReceiptValidation_OK on the ESE_Success (ordinal 0) branch.
 *   Two surgical edits force every outcome to report success:
 *     1. Replace `const/4 v1, 0x0` (match[1], the <init>(Z) arg) with `const/4 v1, 0x1`.
 *     2. Insert `const/4 v1, 0x0` right after the move-result v1 that follows the
 *        EServerErrorCode.ordinal() invoke-virtual, pinning v1=0 so the next
 *        `if-eqz v1, :cond_3f` ALWAYS jumps to the ESE_Success branch.
 *
 * T6 — Microtransaction Policy (Always Allow)
 *   GetInvalidReceiptPolicy() / GetInvalidResponsePolicy() default to Policy.Allow but
 *   consult remote Swrve A/B config that can flip the outcome to Policy.Reject.
 *   Prepend `sget-object v0, Policy->Allow; return-object v0` to BOTH methods, making
 *   the remote lookup dead code. Register budget: .registers 3, block uses only v0.
 *
 * T7 — Skip-Play Direct Grant
 *   BeginPurchase(String)String (MicroTransactionAndroid.smali:170, .registers 7) is
 *   the single choke point for every store tap; normally it routes to
 *   GooglePlayStoreHelper.BeginPurchase → queryProductDetailsAsync → launchBillingFlow
 *   (the Play dialog). Prepend the forge-success block building a SUCCESS
 *   PlatformInterfaceDelegateResult via getResult(ESE_Success, lowercased productId)
 *   and firing it through firePurchaseDone into the native result queue, then
 *   return-object p1 — Play's billing flow is never reached. Register budget: block
 *   uses only v0, v1, p0, p1; .registers unchanged so p0/p1 stay valid.
 *
 * T8 — Billing Connection Bypass (storefront init path)
 *   A re-signed APK is not the build registered in Play, so Play Billing answers
 *   DEVELOPER_ERROR / BILLING_UNAVAILABLE. GooglePlayStoreHelper$2.
 *   onBillingSetupFinished then keeps bIsIapSetup=false and never calls
 *   val$InSetupFinishedListener.onHelperSetupFinished(), so the native store never
 *   receives NativeCallback_MicroTransactionOnInit and the storefront never enables its
 *   buy buttons. The hook forces the helper into the connected state (bIsIapSetup=true,
 *   mSubscriptionsSupported=true, reconnect timer reset) and fires the captured setup
 *   listener so OnInit is delivered. Falls back to stock behaviour if the bridge cannot
 *   resolve the fields.
 *
 * T9 — Offline Store Catalog
 *   MicroTransactionAndroid.refreshProductList(String[]) (MicroTransactionAndroid.smali:2616,
 *   .locals 3) asks Play for every SKU the native store requests; for a re-signed build
 *   that query fails, GotInventoryListener queues ProductListCommand(skus, null) and the
 *   native store receives OnGetAvailableProductsFailed — no items, no buy buttons.
 *   The hook builds a synthetic ProductDetails catalog for exactly the requested SKUs
 *   and queues a real ProductListCommand, so the storefront gets a complete
 *   product/price table without contacting Play. Register budget: v0 + p0/p1.
 *
 * Risk: MEDIUM-HIGH (unchanged from findings) — WBID cloud-save sync is
 * server-authoritative; un-backed grants may be reverted or flagged on next cloud-save
 * load. T4 tolerates offline/fake/unreachable responses; T7 grants instantly on tap.
 *
 * T11 — Network predicate (the real "Cannot Connect To Google Play" gate)
 *   UnrealScript `InjusticeIOSGame.HandlePurchase` (export 8027) checks
 *   `HasNetworkConnection()` FIRST; on false it calls `DisplayCouldNotConnect` and
 *   returns before `BeginPurchase`. The native chain is
 *   UEngine::HasNetworkConnection → CallJava_IsNetConnected → UE3JavaApp.
 *   JavaCallback_IsNetConnected, which returns the app's own `isConnecting ||
 *   isConnected` flags. Those stay false on a re-signed/emulated build, so every GET
 *   is refused. Pin the callback to true; the device-wide connectivity checks are left
 *   untouched (only the engine's store-facing predicate is forced).
 */
@Suppress("unused")
val injusticeBillingBypassPatch = bytecodePatch(
    name = "Injustice Billing Bypass",
    description = "Bypass billing get the items in store for free.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    extendWith("extensions/injustice.mpe")

    execute {
        // T3 — Store availability: flip the hardcoded false to true.
        StoreAvailabilityFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // T4 — Receipt validation bypass.
        // match[0] = new-instance PlatformInterfaceDelegateResult
        // match[1] = const/4 v1, 0x0  (<init>(Z) arg — the Successful=false default)
        // match[2] = invoke-direct <init>(Z)
        val ctorArgIndex = ReceiptVerificationFingerprint.instructionMatches[1].index

        // 1) Force PlatformInterfaceDelegateResult(true).
        ReceiptVerificationFingerprint.method.replaceInstruction(ctorArgIndex, "const/4 v1, 0x1")

        // 2) Force the ordinal switch to always take the ESE_Success branch: the ONLY
        //    invoke-virtual targeting EServerErrorCode in this method is the ordinal()
        //    call that feeds the switch. Pin v1=0 in the following move-result so
        //    `if-eqz v1, :cond_3f` always branches to success.
        val ordinalInvokeIndex = ReceiptVerificationFingerprint.method.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_VIRTUAL &&
                getReference<MethodReference>()?.definingClass == "Lmacrotransaction/ReceiptValidateService_\$EServerErrorCode;"
        }
        ReceiptVerificationFingerprint.method.addInstructions(ordinalInvokeIndex + 2, "const/4 v1, 0x0")

        // T6 — Microtransaction policy: prepend `sget-object v0, Policy->Allow;
        // return-object v0` so the failure/offline policy can never flip to Reject.
        InvalidReceiptPolicyFingerprint.method.addInstructions(0, """
            sget-object v0, $POLICY_ALLOW_REF
            return-object v0
        """.trimIndent())

        InvalidResponsePolicyFingerprint.method.addInstructions(0, """
            sget-object v0, $POLICY_ALLOW_REF
            return-object v0
        """.trimIndent())

        // T7 — Skip-Play direct grant: prepend the forge-success block so the tap never
        // reaches GooglePlayStoreHelper.BeginPurchase / launchBillingFlow.
        BeginPurchaseFingerprint.method.addInstructions(0, """
            invoke-virtual {p1}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;
            move-result-object v1
            sget-object v0, $ESE_SUCCESS_REF
            invoke-static {v0, v1}, $GET_RESULT_REF
            move-result-object v0
            invoke-virtual {p0, v0}, $FIRE_PURCHASE_DONE_REF
            return-object p1
        """.trimIndent())

        // T8 — Billing connection: mark the helper connected and deliver OnInit.
        // If the bridge cannot resolve the app fields, fall through to stock handling.
        BillingSetupStateListenerFingerprint.method.addInstructionsWithLabels(0, """
            invoke-static {p0}, $STORE_BRIDGE->forceSetup(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :stock_setup
            return-void
            :stock_setup
            nop
        """.trimIndent())

        // T9 — Store catalog: answer the native product request locally.
        // If the bridge cannot build the synthetic inventory, fall through to the
        // stock Play query (which reports failure as before).
        ProductListRefreshFingerprint.method.addInstructionsWithLabels(0, """
            invoke-static {p0, p1}, $STORE_BRIDGE->queueProductList(Ljava/lang/Object;[Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :stock_products
            return-void
            :stock_products
            nop
        """.trimIndent())

        // T10 — Unconditional store init. Native blocks purchases (field +0xc4 = 1) until
        // NativeCallback_MicroTransactionOnInit runs; upstream that only happens after the
        // Play Billing connection callback. Queue the app's SetupFinishedCommand here so
        // the store is enabled even when billing never calls back.
        StoreInitFingerprint.method.addInstructions(0, """
            invoke-static {p0}, $STORE_BRIDGE->ensureInit(Ljava/lang/Object;)V
        """.trimIndent())

        // T11 — Network predicate. HandlePurchase() (UnrealScript export 8027) checks
        // HasNetworkConnection() before anything else and bails to DisplayCouldNotConnect
        // ("Cannot Connect To Google Play") when it is false. The Java end of that native
        // chain returns the app's own isConnecting/isConnected flags, which never become
        // true on this build. Pin the callback to true so the script proceeds to the
        // availability check and BeginPurchase.
        NetConnectedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())
    }
}

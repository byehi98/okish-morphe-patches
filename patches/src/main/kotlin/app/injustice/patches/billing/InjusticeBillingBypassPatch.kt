package app.injustice.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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

/**
 * Injustice: Gods Among Us — Billing Bypass (consolidated)
 *
 * Single patch covering all four billing gates for com.wb.goog.injustice 3.5.1. Each
 * fingerprint.method is resolved independently inside the execute block, so the four
 * edits compose without overlap (T4 rewrites the result factory's body, T7 calls the
 * factory at the success invariant; T3 keeps the store reachable; T6 pins the
 * failure/offline grant policy). No shared registers across edits — every injected
 * block is register-safe within its own method exactly as before.
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
 * Risk: MEDIUM-HIGH (unchanged from findings) — WBID cloud-save sync is
 * server-authoritative; un-backed grants may be reverted or flagged on next cloud-save
 * load. T4 tolerates offline/fake/unreachable responses; T7 grants instantly on tap.
 */
@Suppress("unused")
val injusticeBillingBypassPatch = bytecodePatch(
    name = "Injustice Billing Bypass",
    description = "Bypass billing get the items in store for free.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

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
    }
}

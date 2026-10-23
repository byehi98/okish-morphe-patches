package app.pvzfree.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.pvzfree.patches.shared.Constants.COMPATIBILITY_PVZFREE

// Smali descriptors. The \$ escapes stop Kotlin from treating the inner-class
// '$' as a string template. All verified against classes2.dex.
private const val GOOGLE_PLAY = "Lcom/ea/nimble/mtx/googleplay/GooglePlay;"
private const val GOOGLE_PLAY_TRANSACTION = "Lcom/ea/nimble/mtx/googleplay/GooglePlayTransaction;"
private const val TRANSACTION_STATE = "Lcom/ea/nimble/mtx/googleplay/GooglePlayTransaction\$GooglePlayTransactionState;"
private const val PURCHASE_CALLBACK = "Lcom/ea/nimble/mtx/INimbleMTX\$PurchaseTransactionCallback;"
private const val ITEM_GRANTED_CALLBACK = "Lcom/ea/nimble/mtx/INimbleMTX\$ItemGrantedCallback;"
private const val GENERATE_TRANSACTION_ID =
    "$GOOGLE_PLAY->generateTransactionId()Ljava/lang/String;"
private const val UPDATE_RECORD_STATE =
    "$GOOGLE_PLAY->updateGooglePlayTransactionRecordState($GOOGLE_PLAY_TRANSACTION$TRANSACTION_STATE)V"
private const val PURCHASE_COMPLETE =
    "$PURCHASE_CALLBACK->purchaseComplete(Lcom/ea/nimble/mtx/NimbleMTXTransaction;)V"
private const val ITEM_GRANTED_COMPLETE =
    "$ITEM_GRANTED_CALLBACK->itemGrantedComplete(Lcom/ea/nimble/mtx/NimbleMTXTransaction;)V"
private const val STATE_WAITING_FOR_GRANT =
    "$TRANSACTION_STATE->WAITING_FOR_GAME_TO_CONFIRM_ITEM_GRANT:$TRANSACTION_STATE"
private const val STATE_COMPLETE =
    "$TRANSACTION_STATE->COMPLETE:$TRANSACTION_STATE"

/**
 * Plants vs. Zombies FREE — Free IAP (billing forge)
 *
 * The native game (libpvz.so) never talks to Google Play directly. It asks the
 * Nimble MTX bridge (com.ea.nimble.mtx.googleplay.GooglePlay, classes2.dex) to
 * start a purchase and registers com.ea.nimble.bridge.MTXNativeCallback for the
 * result. MTXNativeCallback.purchaseComplete(tx) calls
 * BaseNativeCallback.sendNativeCallback(id, tx, true) — the `true` is the
 * success flag (only unverifiedReceiptReceived passes `false`). Native then
 * reads getTransactionId(), getItemSku(), getItemSellId(), getTransactionState(),
 * getReceipt() and getError(), and grants the reward mapped to that SellID
 * (coins, Ads Free entitlement, speed-up/bundle).
 *
 * Receipt verification is already disabled in this build
 * (`nimble_mtx_enableVerification=false`), so the real non-verified success path
 * is exactly: state = WAITING_FOR_GAME_TO_CONFIRM_ITEM_GRANT + purchaseComplete().
 * This patch reproduces that path without ever calling Play Billing, and then
 * makes the consume/acknowledge completions succeed without a Play token.
 *
 * Register safety (no .registers change anywhere):
 *   purchaseItem          .registers 10 → uses v0..v3, p0..p2
 *   googlePlayConsumeItem .registers 6  → uses v0..v1, p0..p1
 *   googlePlayAcknowledgePurchase .registers 9 → uses v0..v1, p0..p1
 *   isTransactionPending(Z)Z .registers 3 → uses v0
 *   getPendingTransactions()List .registers 3 → uses v0
 *
 * generateTransactionId() and updateGooglePlayTransactionRecordState(...) are
 * private, hence invoke-direct (the app's own code calls them that way too).
 */
@Suppress("unused")
val pvzFreeIapPatch = bytecodePatch(
    name = "PvZ FREE Free IAP",
    description = "Forge in-app purchases so coins, Ads Free and bundles are granted without Google Play payment.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PVZFREE)

    execute {
        // A) purchaseItem: build a fake GooglePlayTransaction, register it as pending,
        //    move it to WAITING_FOR_GAME_TO_CONFIRM_ITEM_GRANT, fire purchaseComplete,
        //    and return null (success). This bypasses the billing-availability check,
        //    the pending-transaction check, queryProductDetailsAsync and
        //    launchPurchaseFlow entirely.
        PurchaseItemFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, $GOOGLE_PLAY_TRANSACTION
                invoke-direct {v0}, $GOOGLE_PLAY_TRANSACTION-><init>()V
                iput-object p1, v0, $GOOGLE_PLAY_TRANSACTION->mItemSku:Ljava/lang/String;
                invoke-direct {p0}, $GENERATE_TRANSACTION_ID
                move-result-object v1
                iput-object v1, v0, $GOOGLE_PLAY_TRANSACTION->mTransactionId:Ljava/lang/String;
                iput-object p2, v0, $GOOGLE_PLAY_TRANSACTION->mPurchaseCallback:$PURCHASE_CALLBACK
                # Native reads getReceipt(); give it a non-empty dummy receipt.
                const-string v3, "xxxxxxxxxxxxxxxxxxxxxxxxx"
                iput-object v3, v0, $GOOGLE_PLAY_TRANSACTION->mReceipt:Ljava/lang/String;
                # mIsRecorded=true keeps the fake tx out of EA Synergy reporting.
                const/4 v3, 0x1
                iput-boolean v3, v0, $GOOGLE_PLAY_TRANSACTION->mIsRecorded:Z
                # Attach the live catalog item so finalizeTransaction does not treat
                # consumables as owned. HashMap.get returns null for unknown SKUs and
                # check-cast on null is safe in Dalvik.
                iget-object v2, p0, $GOOGLE_PLAY->mCatalogItems:Ljava/util/HashMap;
                invoke-virtual {v2, p1}, Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, Lcom/ea/nimble/mtx/googleplay/GooglePlayCatalogItem;
                iput-object v2, v0, $GOOGLE_PLAY_TRANSACTION->mCatalogItem:Lcom/ea/nimble/mtx/googleplay/GooglePlayCatalogItem;
                # Register as pending so itemGranted()/finalizeTransaction can resolve it.
                iget-object v2, p0, $GOOGLE_PLAY->mPendingTransactions:Ljava/util/HashMap;
                invoke-virtual {v2, v1, v0}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                sget-object v3, $STATE_WAITING_FOR_GRANT
                invoke-direct {p0, v0, v3}, $UPDATE_RECORD_STATE
                invoke-interface {p2, v0}, $PURCHASE_COMPLETE
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
        )

        // B) googlePlayConsumeItem: mark the forged tx COMPLETE and deliver
        //    itemGrantedComplete directly. The original body would fail on the
        //    missing Play purchase token and record a NimbleMTXError.
        ConsumeItemFingerprint.method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, $GOOGLE_PLAY_TRANSACTION->mItemGrantedCallback:$ITEM_GRANTED_CALLBACK
                sget-object v1, $STATE_COMPLETE
                invoke-direct {p0, p1, v1}, $UPDATE_RECORD_STATE
                if-eqz v0, :morphe_iap_consume_done
                invoke-interface {v0, p1}, $ITEM_GRANTED_COMPLETE
                :morphe_iap_consume_done
                return-void
            """.trimIndent()
        )

        // C) googlePlayAcknowledgePurchase: same completion for non-consumables
        //    (Ads Free / entitlement bundles).
        AcknowledgePurchaseFingerprint.method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, $GOOGLE_PLAY_TRANSACTION->mItemGrantedCallback:$ITEM_GRANTED_CALLBACK
                sget-object v1, $STATE_COMPLETE
                invoke-direct {p0, p1, v1}, $UPDATE_RECORD_STATE
                if-eqz v0, :morphe_iap_ack_done
                invoke-interface {v0, p1}, $ITEM_GRANTED_COMPLETE
                :morphe_iap_ack_done
                return-void
            """.trimIndent()
        )

        // D) Repeat-purchase hardening.
        //
        // The forged transaction is persisted in mPendingTransactions (and on disk
        // by updateGooglePlayTransactionRecordState), so a crash/kill between the
        // callback and native's itemGranted()/finalizeTransaction leaves a stale
        // pending record. The forged purchase must never be blocked by that record,
        // and native (libpvz.so imports getPendingTransactions) must not try to
        // "recover" it. Both guards are safe to neutralize because every purchase
        // is forged locally and completed synchronously in this patch:
        //   - isTransactionPending(Z)Z: returning false at index 0 is safe because
        //     the monitor-enter happens later in the original body.
        //   - getPendingTransactions(): always return a fresh empty ArrayList.
        IsTransactionPendingFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent()
        )

        GetPendingTransactionsFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                return-object v0
            """.trimIndent()
        )
    }
}

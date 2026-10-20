package app.pvzfree.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.ea.nimble.mtx.googleplay.GooglePlay.purchaseItem(
 *     String, INimbleMTX$PurchaseTransactionCallback, String) -> com.ea.nimble.Error
 * — public.
 *
 * THE single entry point the native game (libpvz.so via the Nimble MTX bridge)
 * calls for every store tap: coins, Ads Free, speed-up/VIP bundles.
 *
 * Confirmed smali: classes2.dex
 * com/ea/nimble/mtx/googleplay/GooglePlay.smali:6066
 *   .method public purchaseItem(Ljava/lang/String;Lcom/ea/nimble/mtx/INimbleMTX$PurchaseTransactionCallback;Ljava/lang/String;)Lcom/ea/nimble/Error;
 *   .registers 10   (p0=this=v6, p1=sku=v7, p2=callback=v8, p3=metadata=v9;
 *                    v0..v5 are free for the injected block)
 *
 * The method's first log call uses the unique format string
 * "purchaseItem() call with sku(%s)" (GooglePlay.smali:6074), which pins the
 * non-obfuscated name + signature to exactly one method. The three-parameter
 * signature itself is already unique inside this class.
 */
object PurchaseItemFingerprint : Fingerprint(
    definingClass = "Lcom/ea/nimble/mtx/googleplay/GooglePlay;",
    name = "purchaseItem",
    returnType = "Lcom/ea/nimble/Error;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Lcom/ea/nimble/mtx/INimbleMTX\$PurchaseTransactionCallback;",
        "Ljava/lang/String;"
    ),
    filters = listOf(
        string("purchaseItem() call with sku(%s)")
    )
)

/**
 * com.ea.nimble.mtx.googleplay.GooglePlay.googlePlayConsumeItem(
 *     GooglePlayTransaction) -> void — private.
 *
 * Consumable completion path. With a forged transaction there is no Play
 * purchase token, so the original body takes the "Purchase token is null"
 * branch and stores a NimbleMTXError instead of granting the item.
 *
 * Confirmed smali: classes2.dex
 * com/ea/nimble/mtx/googleplay/GooglePlay.smali:1400
 *   .method private googlePlayConsumeItem(Lcom/ea/nimble/mtx/googleplay/GooglePlayTransaction;)V
 *   .registers 6   (p0=this=v4, p1=transaction=v5; v0..v3 free)
 *
 * The (GooglePlayTransaction)V private shape is unique in this class; the body
 * starts by reading GOOGLEPLAY_ADDITIONALINFO_KEY_ACKNOWLEDGED.
 */
object ConsumeItemFingerprint : Fingerprint(
    definingClass = "Lcom/ea/nimble/mtx/googleplay/GooglePlay;",
    name = "googlePlayConsumeItem",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/ea/nimble/mtx/googleplay/GooglePlayTransaction;")
)

/**
 * com.ea.nimble.mtx.googleplay.GooglePlay.googlePlayAcknowledgePurchase(
 *     GooglePlayTransaction) -> void — private.
 *
 * Non-consumable (Ads Free / bundle entitlement) completion path. Same missing
 * Play token failure as the consume path; must be short-circuited so
 * itemGrantedComplete is still delivered to the native callback.
 *
 * Confirmed smali: classes2.dex
 * com/ea/nimble/mtx/googleplay/GooglePlay.smali:1253
 *   .method private googlePlayAcknowledgePurchase(Lcom/ea/nimble/mtx/googleplay/GooglePlayTransaction;)V
 *   .registers 9   (p0=this=v7, p1=transaction=v8; v0..v6 free)
 *
 * Body starts with Log$Helper->LOGFUNC then reads
 * GOOGLEPLAY_ADDITIONALINFO_KEY_ACKNOWLEDGED; name + signature are unique.
 */
object AcknowledgePurchaseFingerprint : Fingerprint(
    definingClass = "Lcom/ea/nimble/mtx/googleplay/GooglePlay;",
    name = "googlePlayAcknowledgePurchase",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Lcom/ea/nimble/mtx/googleplay/GooglePlayTransaction;")
)

/**
 * com.ea.nimble.mtx.googleplay.GooglePlay.isTransactionPending(boolean) -> boolean
 * — private.
 *
 * Guard used by purchaseItem (and the no-arg overload) to refuse a new purchase
 * while a previous transaction is still recorded in mPendingTransactions.
 * Because the forged transaction is persisted by
 * updateGooglePlayTransactionRecordState + savePendingTransactionsToPersistence,
 * a stale record must never block later purchases.
 *
 * Confirmed smali: classes2.dex
 * com/ea/nimble/mtx/googleplay/GooglePlay.smali:1638
 *   .method private isTransactionPending(Z)Z
 *   .registers 3   (p0=this=v1, p1=flag=v2; v0 free)
 *
 * The no-arg overload at line 1622 delegates to this one, so forcing this
 * method to return false covers both call sites. Returning at index 0 is safe:
 * the monitor-enter lives further down the original body.
 */
object IsTransactionPendingFingerprint : Fingerprint(
    definingClass = "Lcom/ea/nimble/mtx/googleplay/GooglePlay;",
    name = "isTransactionPending",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Z")
)

/**
 * com.ea.nimble.mtx.googleplay.GooglePlay.getPendingTransactions() -> List
 * — public.
 *
 * Called from native code (libpvz.so imports getPendingTransactions) to recover
 * transactions that were pending when the process died. Since every purchase is
 * forged locally and completed synchronously, native must never see a "pending"
 * record; always hand back an empty list.
 *
 * Confirmed smali: classes2.dex
 * com/ea/nimble/mtx/googleplay/GooglePlay.smali:4990
 *   .method public getPendingTransactions()Ljava/util/List;
 *   .registers 3   (p0=this=v2; v0..v1 free)
 */
object GetPendingTransactionsFingerprint : Fingerprint(
    definingClass = "Lcom/ea/nimble/mtx/googleplay/GooglePlay;",
    name = "getPendingTransactions",
    returnType = "Ljava/util/List;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf()
)

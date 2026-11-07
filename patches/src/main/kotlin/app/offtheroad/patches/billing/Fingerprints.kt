package app.offtheroad.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * `Lcom/utils/InappPurchase;->startPurchase(Ljava/lang/String;)V`
 *
 * The only Java-side entry point of the store. Native
 * `cInappPurchase::startPurchase` (libgame.so `0x8ae408`) reaches it through a
 * cached JNI method id and passes the SKU string. The shipped body logs the SKU,
 * checks the billing client and calls `launchBillingFlow`; the patch replaces it
 * with a direct call to the native grant callback.
 *
 * Verified in `smali/classes/com/utils/InappPurchase.smali:2041`
 * (`.registers 5`, try range starts at `:try_start_33`, i.e. after index 0).
 */
object StartPurchaseFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/InappPurchase;",
    name = "startPurchase",
)

/**
 * `Lcom/utils/InappPurchase;->setNativeInit()V`
 *
 * Called exactly once by the native `cInappPurchase` constructor (the JNI ctor
 * at libgame.so `0x8ad67c` does `CallVoidMethod(instance, mid_setNativeInit)`).
 * It is the engine's "billing bridge is ready" signal, which makes it the right
 * place to replay a synthetic purchase for the VIP subscription.
 *
 * Verified in `smali/classes/com/utils/InappPurchase.smali:1992` (`.registers 2`).
 */
object SetNativeInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/InappPurchase;",
    name = "setNativeInit",
)

/**
 * `Lcom/utils/InappPurchase;->getProductPurchaseTime(Ljava/lang/String;)J`
 *
 * Returns the stored purchase time (unix seconds) for a SKU. The native bridge
 * calls it through JNI for every registered product right before raising the
 * "product data refreshed" event; `cApplication`'s handler uses that value to
 * compute the VIP state (`cApplication+0x478`). The Java table is zeroed by
 * `clearPurchaseTimes()` on every Play query, so for a player who never bought
 * VIP this method returns 0 and the native handler clears the VIP state.
 *
 * Verified in `smali/classes/com/utils/InappPurchase.smali:1533`
 * (`.registers 6`, two params → `p0 = v4`, `p1 = v5`).
 */
object GetProductPurchaseTimeFingerprint : Fingerprint(
    returnType = "J",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/utils/InappPurchase;",
    name = "getProductPurchaseTime",
)

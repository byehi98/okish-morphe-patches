package app.jetpackjoyride.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * GooglePlayPurchaseV2Service.RequestPurchaseInternal(String) — called on the UI thread
 * when the player taps a purchase button. Normally launches the Play billing flow.
 * We return-void immediately after calling SynchronizedOnPurchaseResult with a fake success,
 * so the C++ engine is told the purchase succeeded without ever opening Google Play.
 *
 * Confirmed smali: private static RequestPurchaseInternal(String)V in classes6.dex
 * Stable filter: logs "Attempting to request purchase for" — won't change across app updates.
 */
object RequestPurchaseInternalFingerprint : Fingerprint(
    definingClass = "Lcom/halfbrick/mortar/GooglePlayPurchaseV2Service;",
    name = "RequestPurchaseInternal",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    filters = listOf(
        string("Attempting to request purchase for ")
    )
)

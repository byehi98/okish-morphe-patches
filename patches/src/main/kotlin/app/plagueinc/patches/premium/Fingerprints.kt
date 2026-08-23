package app.plagueinc.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * PurchaseManager.isPremium() — single source of truth for all Java-side
 * premium checks (fast-forward gate, menu lock buttons, difficulty gates).
 *
 * Confirmed smali (classes/com/miniclip/plagueinc/PurchaseManager.smali line 1260):
 *   .method public isPremium()Z
 *     .registers 1
 *     iget-boolean p0, p0, Lcom/miniclip/plagueinc/PurchaseManager;->isPremium:Z
 *     return p0
 */
object IsPremiumFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/PurchaseManager;",
    name = "isPremium",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        fieldAccess(smali = "Lcom/miniclip/plagueinc/PurchaseManager;->isPremium:Z")
    )
)

/**
 * PurchaseManager.<init>(Context) — private constructor, runs once when the
 * singleton is created (well after the native library is loaded by MainActivity,
 * and after SplashActivity has called Main.initApplication).
 *
 * Used as the injection point for the engine entitlement sync (see
 * EngineEntitlementSyncPatch): the game normally only calls Main.setIsPremium /
 * Unlocks.setInAppPurchaseState after a completed purchase (notifyPurchase).
 *
 * Confirmed smali (line 106):
 *   .method private constructor <init>(Landroid/content/Context;)V
 *     .registers 4
 *
 * NOTE: no accessFlags here — constructors carry an extra ACC_CONSTRUCTOR
 * flag which breaks exact flag matching.
 */
object PurchaseManagerInitFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/PurchaseManager;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;")
)

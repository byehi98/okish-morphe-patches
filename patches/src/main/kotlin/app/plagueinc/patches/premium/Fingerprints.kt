package app.plagueinc.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
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

/**
 * DailyLevelSelect.onPlayButtonPressed() — branch point for the outbreak
 * (daily mission) play flow. Used to locate the trial/bonus popup invokes.
 *
 * Confirmed smali (com/miniclip/plagueinc/menu/DailyLevelSelect.smali line 4258):
 *   .method protected onPlayButtonPressed()V
 *     .registers 3
 */
object DailyLevelSelectTrialBranchFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/menu/DailyLevelSelect;",
    name = "onPlayButtonPressed",
    returnType = "V",
    filters = listOf(
        methodCall(smali = "Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->showTrialLevelUx()V")
    )
)

object DailyLevelSelectBonusBranchFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/menu/DailyLevelSelect;",
    name = "onPlayButtonPressed",
    returnType = "V",
    filters = listOf(
        methodCall(smali = "Lcom/miniclip/plagueinc/menu/DailyLevelSelect;->showBonusLevelUx()V")
    )
)

/**
 * DailyLevelSelect.showPurchasePopup() / showOnboardingPopup() — failsafe stubs.
 *
 * Confirmed smali:
 *   showPurchasePopup  line 3181  .method private showPurchasePopup()V
 *   showOnboardingPopup line 3129  .method private showOnboardingPopup()V
 */
object DailyLevelSelectShowPurchasePopupFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/menu/DailyLevelSelect;",
    name = "showPurchasePopup",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE)
)

object DailyLevelSelectShowOnboardingPopupFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/menu/DailyLevelSelect;",
    name = "showOnboardingPopup",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE)
)

/**
 * PurchaseManager.BeginOutbreakPurchaseUx() — opens the Google Play billing
 * sheet for the outbreak SKU. Stubbed to return-void as a failsafe.
 *
 * Confirmed smali (com/miniclip/plagueinc/PurchaseManager.smali line 1164):
 *   .method public BeginOutbreakPurchaseUx()V  .registers 2
 */
object PurchaseManagerBeginOutbreakPurchaseUxFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/PurchaseManager;",
    name = "BeginOutbreakPurchaseUx",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC)
)

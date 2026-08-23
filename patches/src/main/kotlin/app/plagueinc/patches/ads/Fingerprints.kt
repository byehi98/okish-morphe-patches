package app.plagueinc.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * AdController.onGameSessionStarted() — creates and loads the AppLovin MAX
 * banner shown during gameplay, falling back to the Ndemic promo image when
 * no ad fills.
 *
 * Confirmed smali (classes2/com/miniclip/plagueinc/AdController.smali line 386):
 *   .method public onGameSessionStarted()V
 *   Contains: new-instance MaxAdView + const-string "e21aea1fadb44ed2" (banner ad unit)
 */
object OnGameSessionStartedFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/AdController;",
    name = "onGameSessionStarted",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        // AppLovin banner ad unit ID, unique to this method
        app.morphe.patcher.string("e21aea1fadb44ed2")
    )
)

/**
 * AdController.showNdemicAd() — displays the fallback "premium_ad_bg_game"
 * Ndemic promo image instead of a real banner ad.
 *
 * Confirmed smali (line 179):
 *   .method private showNdemicAd()V
 *     sget v0, Lcom/miniclip/plagueinc/R$id;->premium_ad_bg_game:I
 */
object ShowNdemicAdFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/AdController;",
    name = "showNdemicAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    filters = listOf(
        fieldAccess(smali = "Lcom/miniclip/plagueinc/R\$id;->premium_ad_bg_game:I")
    )
)

/**
 * VideoAdController.createRewardedAd(Runnable, Runnable, Runnable) — requests
 * an AppLovin rewarded video. Parameters:
 *   p1 = onReward callback (stored to onReward field)
 *   p2 = onFail   callback
 *   p3 = onClose  callback
 *
 * Confirmed smali (classes8/com/miniclip/plagueinc/VideoAdController.smali line 133):
 *   .method public createRewardedAd(Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;)V
 *     iput-object p1, ... ->onReward:Ljava/lang/Runnable;
 *     iput-object p2, ... ->onFail:Ljava/lang/Runnable;
 *     iput-object p3, ... ->onClose:Ljava/lang/Runnable;
 */
object CreateRewardedAdFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/VideoAdController;",
    name = "createRewardedAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/Runnable;", "Ljava/lang/Runnable;", "Ljava/lang/Runnable;")
)

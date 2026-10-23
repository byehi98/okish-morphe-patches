package app.plagueinc.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * AdController.onGameSessionStarted() — creates and loads the AppLovin MAX
 * banner shown during gameplay, falling back to the Ndemic promo image when
 * no ad fills.
 *
 * Confirmed smali (classes2/com/miniclip/plagueinc/AdController.smali:679):
 *   .method public onGameSessionStarted()V
 *   showNdemicAd() + createBanner(...) + MaxAdView.startAutoRefresh()
 *
 * 1.27.3 hoisted the banner ad-unit literal ("e21aea1fadb44ed2") into the
 * static field `BANNER_AD_UNIT_ID` and moved the `new MaxAdView(...)` into
 * createBanner(), so the old string filter no longer sees it inside this
 * method. Anchor on the two calls the method does make instead.
 */
object OnGameSessionStartedFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/AdController;",
    name = "onGameSessionStarted",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        methodCall(smali = "Lcom/miniclip/plagueinc/AdController;->showNdemicAd()V"),
        methodCall(smali = "Lcom/applovin/mediation/ads/MaxAdView;->startAutoRefresh()V"),
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
 * VideoAdController.createRewardedAd(String, Runnable, Runnable, Runnable) —
 * requests an AppLovin rewarded video. Parameters:
 *   p1 = placement name
 *   p2 = onReward callback (stored to onReward field)
 *   p3 = onFail   callback
 *   p4 = onClose  callback
 *
 * Confirmed smali (classes8/com/miniclip/plagueinc/VideoAdController.smali:170):
 *   .method public createRewardedAd(Ljava/lang/String;Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;)V
 *     iput-object p2, ... ->onReward:Ljava/lang/Runnable;
 *     iput-object p3, ... ->onFail:Ljava/lang/Runnable;
 *     iput-object p4, ... ->onClose:Ljava/lang/Runnable;
 *
 * 1.27.3 added the leading `String placement` parameter (AdController passes
 * the AdMob/MAX placement through). The callback positions therefore shifted
 * by one compared with 1.26.1.
 */
object CreateRewardedAdFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/VideoAdController;",
    name = "createRewardedAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/Runnable;",
        "Ljava/lang/Runnable;",
        "Ljava/lang/Runnable;"
    )
)

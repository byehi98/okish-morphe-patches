package app.doodlejump.patches.resurrection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * AdsMediationManager.handleMessage(ILjava/lang/Object;I)I
 *
 * This is the single Java-side entry point for ALL ad messages coming from the
 * C++ engine. When the player dies and the engine offers "watch ad to
 * resurrect", it sends Msg_Show_Ad (0x1f) with a Messages$MsgShowAdData payload
 * whose adType has the VideoGiftInterstitial (0x8) bit set.
 *
 * This method then posts an AdsMediationManager$1 Runnable that calls the
 * network-specific manager's loadAd(I)V (IronSource / AppLovin / ... — the base
 * AdsMediationManager.loadAd is only a stub). Because the adType is remapped by
 * GetAdStatus before reaching the concrete manager, a per-network patch cannot
 * reliably catch the gift ad. So we intercept HERE, network-independently:
 * if it's a gift (revive) ad we immediately send Msg_Ad_WillAppear (0x20) +
 * Msg_Ad_Rewarded (0x23) to the engine and return, so the ad never loads or
 * shows and the resurrection is granted instantly.
 *
 * Confirmed smali (smali_classes3/.../AdsMediationManager.smali):
 *   588:  .method public handleMessage(ILjava/lang/Object;I)I
 *   636:  packed-switch p1, :pswitch_data_0   (0x1c..0x1f -> pswitch_3..0)
 *   645:  check-cast p2, Messages$MsgShowAdData   (inside pswitch_0 / 0x1f path)
 *   662:  invoke-static {p1}, AgeGateSettings->isAdultUser(Landroid/content/Context;)Z
 *   682:  iget p1, p2, Messages$MsgShowAdData->adType:I
 *   913:  :cond_7 ... new-instance AdsMediationManager$1 ... runOnUiThread (the ad show)
 */
object ResurrectionShowAdFingerprint : Fingerprint(
    definingClass = "Lcom/limasky/doodlejumpandroid/AdsMediationManager;",
    name = "handleMessage",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I", "Ljava/lang/Object;", "I"),
    filters = listOf(
        // Distinctive to this handler (age-gate check inside the 0x1f path).
        methodCall(
            definingClass = "Lcom/limasky/doodlejumpandroid/AgeGateSettings;",
            name = "isAdultUser"
        )
    )
)

/**
 * IronSourceManager.loadAd(I)V  (network-specific backup)
 *
 * Secondary safety net: if a build routes the gift ad straight to IronSource's
 * loadAd (without going through the mediation handleMessage above), this still
 * grants the revive. The concrete managers override the base stub loadAd, so
 * this targets the real IronSource implementation.
 *
 * Confirmed smali (smali_classes4/.../IronSourceManager.smali):
 *   1175: .method public loadAd(I)V   (.locals 4)
 *   1188: const/16 v1, 0x20
 *   1261: invoke-static {p1}, AdType->HasVideoGiftInterstitial(I)Z
 *   1323: invoke-virtual {p0}, IronSourceRewardedVideoObject->show()Z
 */
object ResurrectionLoadAdFingerprint : Fingerprint(
    definingClass = "Lcom/limasky/doodlejumpandroid/IronSourceManager;",
    name = "loadAd",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    filters = listOf(
        literal(0x20), // const/16 v1, 0x20  (Msg_Ad_WillAppear, used by the gift branch)
        methodCall(
            definingClass = "Lcom/limasky/doodlejumpandroid/AdType;",
            name = "HasVideoGiftInterstitial"
        ),
        methodCall(
            definingClass = "Lcom/limasky/doodlejumpandroid/IronSourceRewardedVideoObject;",
            name = "show"
        )
    )
)

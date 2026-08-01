package app.swordigo.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * AdsHelper.canShowInterstitial() — gates interstitial ad display.
 */
object CanShowInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/touchfoo/swordigo/AdsHelper;",
    name = "canShowInterstitial",
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * AdsHelper.showAppOpenAdIfAppropriate() — gates app open ad display.
 */
object ShowAppOpenAdFingerprint : Fingerprint(
    definingClass = "Lcom/touchfoo/swordigo/AdsHelper;",
    name = "showAppOpenAdIfAppropriate",
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * AdsHelper.start() — force _adsDisabledBecausePurchase = true on entry.
 */
object AdsHelperStartFingerprint : Fingerprint(
    definingClass = "Lcom/touchfoo/swordigo/AdsHelper;",
    name = "start",
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * GameRenderer.onSurfaceCreated() — runs on the GL thread.
 * setupNativeInterface() + setupApplication() are called here on first run,
 * so the C++ engine is fully ready. We inject fake purchases at the end of this method.
 */
object OnSurfaceCreatedFingerprint : Fingerprint(
    definingClass = "Lcom/touchfoo/swordigo/GameRenderer;",
    name = "onSurfaceCreated",
    returnType = "V"
)

/**
 * MainActivity$16$8.run() — the game thread runnable that calls finishedRestoringPurchases().
 * This is the CORRECT thread to call productPurchased() from.
 * We inject our 3 products right before finishedRestoringPurchases() every time.
 */
object FinishedRestoringRunnableFingerprint : Fingerprint(
    definingClass = "Lcom/touchfoo/swordigo/MainActivity\$16\$8;",
    name = "run",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/touchfoo/swordigo/Native;",
            name = "finishedRestoringPurchases"
        )
    )
)

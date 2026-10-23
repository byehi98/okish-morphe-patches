package app.headsoccer.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ── Head Soccer 7.1.8 (versionCode 352) — ad-surface fingerprints ──────────
//
// Verified against the shipped smali:
//   analysis/head-soccer/smali/classes1/com/dnddream/headsoccer/android/headsoccer.smali:3448
//
// 7.1.8 REPLACED the AppLovin rewarded interstitial with **Google AdMob**
// (`com.google.android.gms.ads.rewarded.RewardedAd`). The old
// `AppLovinIncentivizedInterstitial.isAdReadyToDisplay/show` anchors no longer exist in the
// app DEX (the only remaining "applovin" tokens are Unity mediation reflection stubs), which
// is why the 7.1.6/7.1.7 fingerprint failed. All third-party anchors below are Google/cocos
// names and cannot be obfuscated; the app-private `headsoccer` class/method name is a
// debug-style name in an app that has never been ProGuarded.

/**
 * `headsoccer.ShowAds()V` — smali line 3448, `.registers 4`, **NO try/catch table**.
 *
 * The store/menu "watch ad" entry point. Reached from native through:
 *
 * ```
 * native  → headsoccer.ShowAdver(int)                    static, headsoccer.smali:3222
 *   → te.handler.post(headsoccer$21)  ───────────────────► UI thread
 *     → headsoccer$21.run() → te.ShowAds()               headsoccer$21.smali
 * ```
 *
 * Shipped body, in order:
 * 1. `if (mRewardedAd != null)` → queue `headsoccer$26` → `SuccessAds(1)`, set
 *    `m_bRewardOK = false`, `m_bVideoCompleted = false`, `m_bShowAdver = true`, then
 *    `rewardedAd.setFullScreenContentCallback($27)` and
 *    `rewardedAd.show(this, $28)` — AdMob full-screen callback + earned-reward listener.
 * 2. otherwise (no loaded ad) → `m_bShowAdver = false`, queue `headsoccer$29` →
 *    `SuccessAds(0)`, then `LoadRewardedAd()`.
 *
 * The reward only lands via the **AdMob callbacks**:
 * * `headsoccer$28.onUserEarnedReward(...)` → `m_bRewardOK = true`, `m_bVideoCompleted = true`
 * * `headsoccer$27.onAdDismissedFullScreenContent()` → **the only grant site**:
 *   ```
 *   m_bShowAdver = false;
 *   if (m_bRewardOK) mGLView.queueEvent(headsoccer$27$1);   // → RewardOK()   [native]
 *   m_bRewardOK = false;  m_bVideoCompleted = false;
 *   hideSystemUI();  LoadRewardedAd();
 *   ```
 *
 * and `headsoccer$27$1.run()` is just `headsoccer.RewardOK()`. `onAdFailedToShowFullScreenContent`
 * only clears the flags and reloads.
 *
 * The fingerprint is built from the AdMob/GL-thread calls, which occur in this exact trio
 * **only** in `ShowAds()`. Other classes call `setFullScreenContentCallback` on
 * InterstitialAd/AppOpenAd, and Unity has its own `RewardedAd`; pinning
 * `com.google.android.gms.ads.rewarded.RewardedAd` + the app class keeps the match unique.
 */
object ShowAdsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/dnddream/headsoccer/android/headsoccer;",
    name = "ShowAds",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/rewarded/RewardedAd;",
            name = "setFullScreenContentCallback"
        ),
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/rewarded/RewardedAd;",
            name = "show"
        ),
        methodCall(
            definingClass = "Lorg/cocos2dx/lib/Cocos2dxGLSurfaceView;",
            name = "queueEvent"
        ),
    )
)
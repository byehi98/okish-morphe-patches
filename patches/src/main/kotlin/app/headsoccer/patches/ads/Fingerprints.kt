package app.headsoccer.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// ── Head Soccer 7.1.6 (versionCode 345) — ad-surface fingerprints ──────────
//
// Verified against the shipped smali:
//   analysis/head-soccer/smali/classes/com/dnddream/headsoccer/android/headsoccer.smali
//
// Unlike the billing patch, these two are anchored almost entirely on THIRD-PARTY names
// (`com.applovin.*`, `org.cocos2dx.lib.*`) that can never be obfuscated, so they are safe
// across updates on their own — the app-private `headsoccer` class/method name is the only
// non-stable token, and it is a debug-style name in an app that has never been ProGuarded.

/**
 * `headsoccer.ShowAds()V` — smali line 3943, `.registers 9`, **NO try/catch table**.
 *
 * The store/menu "watch ad" entry point. Reached from native through:
 *
 * ```
 * native  → headsoccer.ShowAdver(int)                    static, smali:867
 *   → te.handler.post(headsoccer$25)  ───────────────────► GL thread
 *     → headsoccer$25.run() → te.ShowAds()               headsoccer$25.smali:40
 * ```
 *
 * Shipped body, in order:
 * 1. `if (SDK_INT < 14)` → queue `headsoccer$32` → `SuccessAds(0)` (the "too old / failed" path)
 * 2. `if (!myIncent.isAdReadyToDisplay())` → queue `headsoccer$31` → `SuccessAds(0)` (no-ad path)
 * 3. otherwise → queue `headsoccer$26` → `SuccessAds(1)`, set the three `m_b*` flags, then
 *    `myIncent.show(ctx, $30, $28, $27, $29)` — five AppLovin listeners.
 *
 * The reward only lands via the **AppLovin callbacks**:
 * * `headsoccer$28.videoPlaybackEnded(...)` → `m_bVideoCompleted = p4`
 * * `headsoccer$29.userRewardVerified(...)` → logs only; `validationRequestFailed` clears the flag
 * * `headsoccer$27.adHidden(...)` → **the only grant site**:
 *   ```
 *   if (m_bRewardOK && m_bVideoCompleted) {
 *       Log.d("applovin", "applovin reward ok!!");
 *       mGLView.queueEvent(headsoccer$27$1);      // → access$19 → RewardOK()   [native]
 *   }
 *   m_bVideoCompleted = false;  m_bRewardOK = false;
 *   ```
 *
 * and `headsoccer$27$1.run()` is literally just
 * `invoke-static { this$0 }, headsoccer.access$19(...)` → `RewardOK()`.
 *
 * The fingerprint is built from the AppLovin/GL-thread calls, which occur in this exact trio
 * **only** in `ShowAds()`. `playRewarded(View)` (smali 6579) also touches `myIncent`, but it
 * passes **two null listeners** to a 4-arg `show(...)` overload and never calls `queueEvent`,
 * so the 3-filter combination cannot match it.
 */
object ShowAdsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    definingClass = "Lcom/dnddream/headsoccer/android/headsoccer;",
    name = "ShowAds",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/applovin/adview/AppLovinIncentivizedInterstitial;",
            name = "isAdReadyToDisplay"
        ),
        methodCall(
            definingClass = "Lcom/applovin/adview/AppLovinIncentivizedInterstitial;",
            name = "show"
        ),
        methodCall(
            definingClass = "Lorg/cocos2dx/lib/Cocos2dxGLSurfaceView;",
            name = "queueEvent"
        ),
    )
)
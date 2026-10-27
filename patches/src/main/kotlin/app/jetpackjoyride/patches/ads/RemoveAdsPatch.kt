package app.jetpackjoyride.patches.ads

import app.jetpackjoyride.patches.shared.Constants.COMPATIBILITY_JETPACKJOYRIDE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val PATCH_NAME = "Remove Ads"

/** Shipped frame: `v0`/`v1`/`v2` free, `p0 = v3` (this), `p1 = v4` (placement). */
private const val INTERSTITIAL_SHOW_AD_REGISTERS = 5

/**
 * Jetpack Joyride — Remove Ads
 * (com.halfbrick.jetpackjoyride v1.104.2 / versionCode 891646000)
 *
 * Full-screen interstitial adverts stop appearing entirely. Nothing is hidden
 * with an overlay and nothing is frozen: the app's **own** "no ad available"
 * branch is replayed, so the game's ad state machine completes normally and the
 * next run starts exactly as if the network had returned no fill.
 *
 * ## The one show site (all of `classes6.dex`)
 *
 * ```
 * native libmortargame.so
 *   └─ Provider_IronsourceMediationBackend_Java::ShowInterstitialAd(const char*)  [JNI upcall]
 *        └─ Provider_IronsourceMediationBackend.ShowInterstitialAd(String)V  @411
 *             └─ runOnUiThread($$ExternalSyntheticLambda6)                  @434
 *                  └─ lambda$ShowInterstitialAd$4(String)                   @954
 *                       └─ AdUnitCollection.ShowAd(String)                  @523  (shared!)
 *                            └─ InterstitialAd.ShowAd(String)Z              @284  ← hook
 *                                 ├─ !IsReady() → OnAdClosed(false); return false   @317-322
 *                                 └─ LevelPlayInterstitialAd.showAd(activity, placement)  @345
 * ```
 *
 * `InterstitialAd.ShowAd` is the **only** place in app code that presents an
 * interstitial, and it is reached only from that single JNI entry point. It is a
 * purely DEX choke point — nothing about interstitial suppression needs native
 * work.
 *
 * ## What is forced
 *
 * The whole body is replaced by the app's own no-fill branch, moved to offset 0:
 *
 * ```
 * mListener.OnAdClosed(false)   → InterstitialAdListener.OnAdClosed(Z)
 *                               → INativeBridge.InterstitialAdClosed(false)
 *                               → ThreadSafeNativeBridge (queues onto the native thread)
 *                               → MortarNativeBridge.InterstitialAdClosed(Z) (JNI)
 *                               → native OnInterstitialAdShown(false)
 *                               → AdvertisingServiceBaseSingleAd::OnAdShown(false)
 * return false
 * ```
 *
 * That is exactly what the game does today when LevelPlay has no fill, i.e. a
 * state the native engine already handles on every offline session. The advert
 * is never handed to `LevelPlayInterstitialAd.showAd`, so no Activity, WebView
 * or SDK UI is ever created. `LevelPlayInterstitialAd.loadAd()` is **not**
 * touched: loading keeps working and is simply thrown away, which keeps the
 * change to one instruction-level intervention on the show path.
 *
 * ## Why the fake callback is honoured by native
 *
 * Unlike the rewarded path, `Provider_IronsourceMediationBackend::ShowInterstitialAd`
 * (`libmortargame.so` 0x144e830) sets **no** "shown" flag before its JNI upcall
 * — there is no `+0x1d` write, no latch. `InterstitialAdClosed(bool)` lands in
 * `OnInterstitialAdShown(bool)` → `AdvertisingServiceBaseSingleAd::OnAdShown(bool)`
 * and that is the normal "the interstitial did not show" result. So reporting
 * `false` leaves **no latched state** to unblock: there is nothing native is
 * waiting on that we would have to fake.
 *
 * ## Register budget
 *
 * The method ships `.registers 5`; being an instance method with one `String`
 * parameter that means `p0 = v3`, `p1 = v4` and `v0`/`v1`/`v2` are free locals.
 * The injected body needs `v0` (listener) and `v1` (the `false`, also the return
 * value) — both already free — so the frame is **not** grown and no parameter
 * register is read. `requireRegisterCount` proves the frame after the fact.
 * `InterstitialAd.smali` has **zero** `.catch` directives, so the early
 * `return-void`-style exit cannot leave a try range dangling.
 *
 * ## Threading
 *
 * `Provider_IronsourceMediationBackend.ShowInterstitialAd` is a JNI upcall and
 * posts the real work with `runOnUiThread`, so `InterstitialAd.ShowAd` always
 * executes on the **Android UI thread**. The injected listener call is cheap and
 * non-blocking: `ThreadSafeNativeBridge` never calls JNI inline, it queues a
 * Runnable through `NativeGameLib.queueNativeThreadTask(...)`, which runs later
 * on the game's native thread holding `NativeGameLib.GetSyncObj()`. `mListener`
 * is a `final` field assigned in the constructor, so the `invoke-interface` can
 * only fail if the object itself is broken.
 *
 * ## Banners
 *
 * Nothing to patch: Jetpack Joyride 1.104.2 ships **no banner integration in its
 * Java bridge**. `LevelPlayBannerAdView` exists only as an unused SDK class plus
 * `R.styleable` attribute constants — `rg 'LevelPlayBanner' classes6/com/halfbrick/`
 * matches resource ids only, and there is no `new-instance … LevelPlayBannerAdView`
 * anywhere outside `com/unity3d`/`com/ironsource` internals. If a banner is ever
 * enabled it would be driven from native/the SDK's own view, which is out of
 * scope for a DEX patch. This patch therefore does exactly one thing.
 *
 * ## DO NOT (from `analysis/jetpackjoyride/notes/ad-rewards-and-remove-ads.md`)
 *
 *  * **Never** patch `Provider_IronsourceMediationBackend.ShowInterstitialAd`
 *    with a bare `return-void`: native would never receive
 *    `InterstitialAdClosed(…)` and its ad-space state machine would wait forever.
 *    If that site is ever used it must synthesise `mInterstitialListener.OnAdClosed(false)`.
 *  * **Never** stub `AdUnitCollection.ShowAd` — it is the shared dispatcher for
 *    interstitials *and* rewarded units, and stubbing it would fire rewarded
 *    callbacks for interstitial requests (or vice versa).
 *  * **Never** call `LevelPlayInterstitialAd.loadAd()`/`showAd()` from the
 *    injected code, and never fake `OnAdLoaded(true)` for the interstitial: that
 *    would advertise a non-existent advert as ready and route the show path back
 *    into `InterstitialAd.ShowAd`.
 *  * **Never** no-op `InterstitialAd.LoadAd()` instead: `AdUnitCollection.LoadAd`
 *    sets `mLoadingPending = true` and only clears it via `OnAdLoaded(Z)`, so a
 *    silent no-op leaves native waiting for `InterstitialAdLoaded` forever.
 *  * **Never** add a `.catch`/`try` block: the original method has none and the
 *    injected code cannot throw in a way that would need handling.
 *
 * Pure DEX: no `rawResourcePatch`, no `dependsOn`, no extension. Independent of
 * [`jetpackJoyrideFreeAdRewardsPatch`] — this patch touches `InterstitialAd`,
 * that one touches `Provider_IronsourceMediationBackend`, so either may be
 * applied alone and there is no ordering constraint between them.
 */
@Suppress("unused")
val jetpackJoyrideRemoveAdsPatch = bytecodePatch(
    name = PATCH_NAME,
    description = "Stops the full-screen interstitial adverts Jetpack Joyride shows between " +
        "runs. The game's own \"no ad available\" path is replayed, so the run continues " +
        "immediately instead of freezing or waiting on a skipped advert.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JETPACKJOYRIDE)

    execute {
        // ── Fail loudly rather than ship an APK with a silently skipped hook:
        //    an unresolved fingerprint here means adverts keep playing. ──
        InterstitialAdShowAdFingerprint.matchAllOrNull()
            .requireExactlyOne("InterstitialAd.ShowAd(String)Z", PATCH_NAME)

        // `.registers 5`: p0 = v3, p1 = v4, v0..v2 free — the body below fits
        // without growing the frame, and the original IsReady()/logging/showAd
        // body stays as unreachable dead code (the method has no `.catch`, so no
        // try range can dangle). `return v1` returns the literal 0 that was just
        // passed to OnAdClosed(false), matching the app's own `return v1` at :322.
        InterstitialAdShowAdFingerprint.method.addInstructions(
            0,
            """
                # InterstitialAd.mListener.OnAdClosed(false) — the app's own
                # "no ad available" branch (:317-322), replayed verbatim:
                #   InterstitialAdListener.OnAdClosed -> INativeBridge.InterstitialAdClosed(false)
                #   -> MortarNativeBridge.InterstitialAdClosed(Z) (JNI)
                #   -> native OnInterstitialAdShown(false) — no latched state.
                iget-object v0, p0, $INTERSTITIAL_AD->mListener:$AD_UNIT_LISTENER
                const/4 v1, 0x0
                invoke-interface {v0, v1}, $AD_UNIT_LISTENER->OnAdClosed(Z)V

                # Caller (AdUnitCollection.ShowAd) ignores the boolean, but return
                # the same false the shipped branch returns so logs read sanely.
                # LevelPlayInterstitialAd.showAd(...) at :345 is never reached.
                return v1
            """.trimIndent()
        )

        // Belt and braces: the injected body addresses v0/v1 inside the shipped
        // 5-register frame; fail the patch if that frame ever changes.
        InterstitialAdShowAdFingerprint.classDef.requireRegisterCount(
            methodName = "ShowAd",
            parameterTypes = listOf("Ljava/lang/String;"),
            expectedRegisters = INTERSTITIAL_SHOW_AD_REGISTERS,
            patchName = PATCH_NAME
        )
    }
}

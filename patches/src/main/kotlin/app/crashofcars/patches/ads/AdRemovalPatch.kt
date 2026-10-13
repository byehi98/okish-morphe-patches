package app.crashofcars.patches.ads

import app.crashofcars.patches.shared.Constants.COMPATIBILITY_CRASH_OF_CARS
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable

/**
 * Descriptor shared by every `MaxUnityAdManager` method this patch family
 * touches: `(adUnitIdentifier, placement, customData)`.
 */
internal val SHOW_PARAMETERS = listOf(
    "Ljava/lang/String;",
    "Ljava/lang/String;",
    "Ljava/lang/String;"
)

/**
 * `showInterstitial` ships with `.registers 5` (locals `v0`, `p0=v1`, `p1=v2`,
 * `p2=v3`, `p3=v4`). Three locals are budgeted for the replacement body, so the
 * frame grows to **7** (`v0..v2` locals, `p0=v3`, `p1=v4`, `p2=v5`, `p3=v6`).
 */
private const val SHOW_INTERSTITIAL_REGISTERS = 7

private const val PATCH_NAME = "Crash of Cars No Ads"

/**
 * Crash of Cars — No Ads (interstitials)
 * (com.notdoppler.crashofcars v1.9.34 / versionCode 1090034)
 *
 * Stops the full-screen ad that plays at the end of every race, without
 * breaking the end-of-race flow.
 *
 * ## Where the ad actually comes from
 *
 * The game does **not** use the Unity LevelPlay WebView ad player. Il2CppDumper
 * plus AArch64 disassembly of `libil2cpp.so` shows the whole ad abstraction is a
 * hand-written wrapper around **AppLovin MAX 13.6.4**:
 *
 * ```
 * com.notdoppler.ads.AdController → IAdNetwork _adNetwork
 *     └─ MaxAdNetwork : MaxSdkAndroid : MaxSdkBase : AndroidJavaProxy
 * AdNetworkFactory.CreateAdNetwork  RVA 0x23BF070   → MaxAdNetwork::.ctor (unconditional)
 * ```
 *
 * and the only C# site that ever dispatches `IAdNetwork.ShowInterstitial`
 * (interface slot 5, `mov w2, #5`) is `UIEndScreen::SetupInterstitialAds`
 * (RVA `0x20C512C`). From there:
 *
 * ```
 * MaxAdNetwork::ShowInterstitial   RVA 0x23BDEC4
 *   └─ MaxSdkAndroid::ShowInterstitial   RVA 0x370763C
 *        └─ MaxUnityAdManager.showInterstitial(String,String,String)V   ← patched here
 *             └─ MaxInterstitialAd.showAd(…) → MaxFullscreenAdImpl.showAd(…)  (presents)
 * ```
 *
 * `Lcom/unity3d/ads/adplayer/` (`CommonWebViewBridge`, `WebViewAdPlayer`,
 * `AndroidFullscreenWebViewAdPlayer`) is a **mediated network *inside* AppLovin**,
 * reached only *through* it. Cutting the MAX show entry point therefore also cuts
 * it; patching the WebView player instead achieves nothing. See
 * `analysis/com.notdoppler.crashofcars/notes/ad-patches.md` §0.
 *
 * ## Why the replacement is NOT a bare `return-void`
 *
 * `MaxAdNetwork.ShowInterstitial` stores the game's close callback into
 * `_onInterstitialClosed` (field offset `0x58`) **before** calling down into Java.
 * The only thing that ever invokes it is `OnInterstitialDismissedEvent`, which is
 * driven by the Java `OnInterstitialHiddenEvent`. A no-op therefore leaves
 * `UIEndScreen` waiting forever on `onInterstitialClosed` (RVA `0x20C5110`) and
 * the end-of-race screen hangs — a strictly worse outcome than the ad.
 *
 * So the replacement forwards the SDK's own lifecycle events through the SDK's
 * own dispatcher, in the SDK's own payload shape:
 *
 * | Event forwarded | Lands in | Does |
 * |---|---|---|
 * | `OnInterstitialDisplayedEvent` | `MaxAdNetwork::OnInterstitialDisplayedEvent` (RVA `0x23BE68C`-adjacent handler) | nothing that blocks — keeps the game's view of the ad lifecycle consistent |
 * | `OnInterstitialHiddenEvent` | `MaxAdNetwork::OnInterstitialDismissedEvent` | `LoadInterstitial(); AdAudioUtils.SetAdShowing(false); AnalyticsTracker.AddAFInterstitialView(…); if (_onInterstitialClosed != null) _onInterstitialClosed()` — **this is the call the game is blocked on** |
 *
 * `MaxInterstitialAd.showAd(...)` is never reached, so no ad Activity is ever
 * created and nothing is displayed.
 *
 * This is the same shape as community technique **T4** ("force-true + fire the
 * callbacks"), applied one layer lower down; see
 * `notes/community-catalog.md` §4.
 *
 * ## Helpers used (all verified in the shipped smali)
 *
 * | Helper | Line | Why it is safe |
 * |---|---|---|
 * | `private MaxAd getAd(String)MaxAd` | `:983` | map lookup under `monitor-enter adInfoMapLock`, returns `null` for an unknown unit, never throws |
 * | `private JSONObject getDefaultAdEventParameters(String, MaxAd)JSONObject` | `:1444` | `getAdInfo(ad)` + `putString(json, "name", eventName)` — **the exact payload the SDK itself builds at `:3989` / `:4036` / `:4091`** |
 * | `private static void forwardUnityEvent(JSONObject)V` | `:954` | posts to `threadPoolExecutor` (or runs inline on the main thread) → `MaxSdkAndroid.BackgroundCallbackProxy.onEvent` → `MaxSdkBase::HandleBackgroundCallback` → the C# handlers |
 *
 * ⚠️ **`forwardUnityEvent` is dispatched with `invoke-static {v1}`, not
 * `invoke-direct {p0, v1}`.** `notes/ad-patches.md` §2d writes the latter, but
 * `forwardUnityEvent` is declared `.method private static …` at `:954` and takes
 * exactly one parameter — `invoke-direct {p0, v1}` is a two-register call into a
 * static method, which the dex verifier rejects. All 16 in-SDK call sites use
 * `invoke-static {vX}` (`:1591`, `:1672`, `:3552`, `:3994`, `:4041`, `:4166`,
 * `:4221`, `:4268`, `:4323`, `:4530`, `:4685`, `:4797`, `:4886`, `:4979`,
 * `:5053`, and the reward path at `:5053`). Same for
 * `getDefaultAdEventParameters` (`:1444`), which is a `private` **instance**
 * method and *is* correctly reached with `invoke-direct {p0, v1, v0}` — exactly
 * as the SDK calls it at `:3989` / `:4036` / `:4091`.
 *
 * `getAd()` returning `null` is guarded with `if-eqz`, because
 * `getDefaultAdEventParameters(name, null)` would NPE inside `getAdInfo` on
 * `MaxAd.getAdUnitId()`. On that path no event is forwarded at all — the
 * interstitial is simply skipped. It cannot be hit in practice on the
 * interstitial unit because `MaxAdNetwork.Init` pre-loads it (which is also what
 * populates `adInfoMap`), but it is guarded rather than assumed.
 *
 * ## Deliberately NOT patched
 *
 * - **`MaxUnityAdManager.loadInterstitial(String)V`** (`:6885`) — no-op'ing the
 *   loader would stop the ad *requests*, but the injected body needs the
 *   `adInfoMap` entry that loading produces to build a valid payload, so cutting
 *   it would turn every interstitial into the silent-skip path above.
 * - **Any entitlement getter** (T6 in `notes/community-catalog.md` §4) — forcing
 *   an "ads disabled" entitlement constant bypasses the game's own consume/reset
 *   cycle and produces the documented *"purchase complete" popup spamming
 *   forever* regression. The entitlement-style path is
 *   `purchase_remove_ads`, which the Free IAP patch already grants; this patch is
 *   deliberately independent of it so it works for players who never bought
 *   anything.
 * - **Rewarded video** — handled separately by
 *   `crashOfCarsInstantAdRewardsPatch`, which keeps the two behaviours
 *   independently switchable.
 * - **`Lcom/unity3d/ads/adplayer/`** — wrong stack, see above.
 *
 * Ad *requests* still reach the network under this approach (MAX keeps loading in
 * the background). Only the display is suppressed; that is inherent to patching at
 * the show entry point rather than at SDK init.
 */
@Suppress("unused")
val crashOfCarsNoAdsPatch = bytecodePatch(
    name = "Crash of Cars No Ads",
    description = "Removes the full-screen advert that plays at the end of every race, so the results screen comes up straight away instead of behind an ad you have to sit through or skip. The race still finishes normally — the game is told the advert opened and closed exactly as it would have been, so nothing gets stuck waiting for it. Works offline and survives restarts, because no part of the ad display depends on a network round trip.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CRASH_OF_CARS)

    execute {
        // ── Fail loudly rather than ship an APK with a silently skipped hook ──
        MaxInterstitialShowFingerprint.matchAllOrNull()
            .requireExactlyOne(
                "MaxUnityAdManager.showInterstitial(String,String,String)V",
                PATCH_NAME
            )

        // ── Frame expansion ──────────────────────────────────────────────────
        //
        // The shipped method is `.registers 5`: locals `v0`, `p0=v1`, `p1=v2`,
        // `p2=v3`, `p3=v4`. The replacement body needs `v0` (the `MaxAd`) and
        // `v1` (event name, then the built `JSONObject`), and `v2` is kept as
        // spare head-room, so the frame must become **`.registers 7`** with
        // `p0=v3`, `p1=v4`, `p2=v5`, `p3=v6`.
        //
        // ⚠️ `addInstructions(0, …)` **cannot** do this: `registerCount` on
        // `MutableMethodImplementation` is a dexlib2 `final` field, so the new
        // instructions would be assembled against a 5-register frame, the Morphe
        // build would still be green, and the APK would fail dex verification on
        // the device. The frame is therefore grown explicitly with
        // `cloneMutable(additionalRegisters = 2)`, the clone is swapped into the
        // class, and `requireRegisterCount` re-reads the result *from the class* to
        // prove the swap landed and the count is 7.
        val original = MaxInterstitialShowFingerprint.method
        val expanded = original.cloneMutable(additionalRegisters = 2)
        MaxInterstitialShowFingerprint.classDef.methods.apply {
            if (!remove(original)) {
                throw PatchException(
                    "$PATCH_NAME: could not remove the original showInterstitial from" +
                        " $MANAGER — the frame expansion would silently not apply."
                )
            }
            add(expanded)
        }

        val patched = MaxInterstitialShowFingerprint.classDef.requireRegisterCount(
            methodName = "showInterstitial",
            parameterTypes = SHOW_PARAMETERS,
            expectedRegisters = SHOW_INTERSTITIAL_REGISTERS,
            patchName = PATCH_NAME
        )

        // ── Replacement body ─────────────────────────────────────────────────
        //
        // Injected at index 0 in front of the original six instructions, which
        // stay below as unreachable dead code (the method has no exception table,
        // so there is no handler region that could be entered around it).
        //
        // `p1` (adUnitIdentifier) is still live for the `getAd` lookup and is not
        // touched afterwards; `p2`/`p3` (placement, customData) are never read
        // again by this body, because the events are dispatched straight to the
        // SDK's Unity-event channel rather than to `MaxInterstitialAd.showAd`.
        // ⚠️ Only `$MANAGER` is a full descriptor (it is also used as a
        // Fingerprint `definingClass`); `$MAX_AD` / `$JSON_OBJECT` / `$JSON_UTILS`
        // are bare type names that the `;` next to them terminates. Emitting one
        // `;` too many (`Lcom/applovin/mediation/MaxAd;;`) is a smali **lexer**
        // error — `Error for input ';': Invalid text`, raised by the inline
        // compiler at patch time, not by `./gradlew buildAndroid`.
        patched.addInstructionsWithLabels(0, """
            # MaxAd ad = getAd(adUnitIdentifier)   (private, :983 — map lookup,
            # monitor-guarded, returns null for an unknown unit, never throws)
            invoke-direct {p0, p1}, $MANAGER->getAd(Ljava/lang/String;)$MAX_AD;
            move-result-object v0
            if-eqz v0, :morphe_interstitial_skipped

            # 1/2 — OnInterstitialDisplayedEvent
            #   -> MaxAdNetwork::OnInterstitialDisplayedEvent
            #      (keeps the game's view of the ad lifecycle consistent)
            const-string v1, "OnInterstitialDisplayedEvent"
            invoke-direct {p0, v1, v0}, $MANAGER->getDefaultAdEventParameters(Ljava/lang/String;$MAX_AD;)$JSON_OBJECT;
            move-result-object v1
            # `forwardUnityEvent` is `private static` (:954), so this MUST be
            # invoke-static with ONE register. All 16 in-SDK call sites use
            # `invoke-static {vX}` (:1591, :1672, :3552, … :5053).
            invoke-static {v1}, $MANAGER->forwardUnityEvent($JSON_OBJECT;)V

            # 2/2 — OnInterstitialHiddenEvent
            #   -> MaxAdNetwork::OnInterstitialDismissedEvent  (RVA 0x23BE68C)
            #      LoadInterstitial(); AdAudioUtils.SetAdShowing(false);
            #      AnalyticsTracker.AddAFInterstitialView(...);
            #      if (_onInterstitialClosed != null) _onInterstitialClosed();
            #   ^ the _onInterstitialClosed() call is what releases UIEndScreen.
            const-string v1, "OnInterstitialHiddenEvent"
            invoke-direct {p0, v1, v0}, $MANAGER->getDefaultAdEventParameters(Ljava/lang/String;$MAX_AD;)$JSON_OBJECT;
            move-result-object v1
            invoke-static {v1}, $MANAGER->forwardUnityEvent($JSON_OBJECT;)V

            # MaxInterstitialAd.showAd(...) is NEVER reached: no ad is created,
            # no ad Activity is started, nothing is displayed.
            :morphe_interstitial_skipped
            return-void
        """.trimIndent())
    }
}

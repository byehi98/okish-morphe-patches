package app.minutestilldawn.patches.ads

import app.minutestilldawn.patches.pairip.pairipBypassPatch
import app.minutestilldawn.patches.shared.Constants.COMPATIBILITY_20MTD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableLongEncodedValue

private const val MAX_PLUGIN = "Lcom/applovin/mediation/unity/MaxUnityPlugin;"

/**
 * Combined ad patch for 20 Minutes Till Dawn — removes every ad *and* grants the
 * rewarded-ad reward without ever playing a video.
 *
 * ## Why the plugin and not the manager
 *
 * The Java side of AppLovin MAX Unity 8.0.1 is two classes:
 *
 *   C# `MaxSdk.*`  ->  `MaxUnityPlugin` (public static JNI surface)
 *                  ->  `MaxUnityAdManager` (instance state + event forwarding)
 *                  ->  `forwardUnityEvent(JSON)` -> C# `MaxSdkCallbacks`
 *
 * **Every** `MaxUnityPlugin` ad method starts with the same guard:
 *
 *     sget-boolean v0, MaxUnityPlugin->initializeSdkCalled:Z
 *     if-nez v0, :cond_0
 *     <log "uninitialized access">; return
 *
 * so hooking only `MaxUnityAdManager` is not enough — the SDK key / ad-unit list
 * arrive as PairIP-virtualised string-pool values, and a re-signed build can
 * easily end up with `initializeSdkCalled == false`, in which case the manager is
 * never reached at all. Replacing the plugin entry points makes the guard
 * irrelevant.
 *
 * ## Why the events are emitted through the SDK itself
 *
 * `MaxUnityAdManager.onAdLoaded / onAdDisplayed / onAdHidden (MaxAd)` and
 * `onUserRewarded(MaxAd, MaxReward)` are **public instance** methods that simply
 * post a Runnable to `threadPoolExecutor`. Those Runnables run the SDK's own
 * `lambda$onAd*` bodies, which build the JSON via `getAdInfo(ad)` /
 * `getDefaultAdEventParameters(name, ad)`.
 *
 * The previous patch hand-built an `org.json.JSONObject` containing only
 * `name` and `adUnitId` and called `forwardUnityEvent` directly. That payload is
 * missing every key C# expects from `AdInfo` (`adFormat`, `networkName`,
 * `networkPlacement`, `creativeId`, `placement`, revenue, `dspName`, …), so the
 * C# handlers see a malformed event. Calling the SDK's own `onAd*` methods means
 * the forwarded JSON is byte-identical to a real ad — no JSON is hand-written
 * anywhere in this patch.
 *
 * ## Synthetic MaxAd / MaxReward
 *
 * Neither `MaxRewardedAd` nor the other fullscreen ad classes implement the
 * `MaxAd` interface, so a synthetic one is needed:
 *
 *  - `com/applovin/impl/kf` is a public `MaxAd` implementation whose ctor is
 *    `(String adUnitId, MaxAdFormat format, String)` and whose remaining getters
 *    return null / 0. `getAdInfo` is null-safe (`JsonUtils.putString` +
 *    `TextUtils.isEmpty`), so it produces a complete, well-formed `AdInfo`.
 *  - `MaxReward` is an interface; `com/applovin/impl/mediation/MaxRewardImpl`
 *    exposes `public static create(int amount, String label)` and
 *    `lambda$onUserRewarded$15` NPEs on a null reward, so a real instance is
 *    required (not `null`).
 *
 * ## Why `OnAdLoaded` is emitted from `loadX`
 *
 * The previous patch turned `loadRewardedAd` into a no-op, so
 * `OnRewardedAdLoadedEvent` never fired. Any UI gated on the load callback (the
 * common MAX pattern: enable the "watch ad" button in `OnAdLoadedEvent`) stayed
 * permanently disabled. `loadX` now synthesises the loaded event locally.
 *
 * ## Rewards / removal matrix
 *
 * | format | `loadX` | `showX` | result |
 * |---|---|---|---|
 * | REWARDED / REWARDED_INTERSTITIAL | `OnAdLoaded` | `OnAdDisplayed` + `OnAdReceivedReward` + `OnAdHidden` | reward granted |
 * | INTERSTITIAL / APP_OPEN | `OnAdLoaded` | `OnAdDisplayed` + `OnAdHidden` | nothing shown |
 * | BANNER / MREC | — | whole `MaxUnityPlugin` banner surface no-ops | no ad view, `getBannerLayout`/`getMRecLayout` return `""` |
 *
 * Banner/MREC are neutralised at the plugin surface rather than only by killing
 * `createAdView`: `MaxUnityAdManager.retrieveAdView` returns null once no view
 * exists, and `getAdViewLayout` / `destroyAdView` / `positionAdView` would then
 * NPE. No-oping the whole surface (create/load/show/hide/destroy/update/auto-refresh/
 * set*) keeps the manager's ad-view map empty and never reached.
 *
 * All bodies are *prepended*: the original instructions stay as unreachable dead
 * code after our `return`. Nothing is removed or re-ordered, so the SDK's
 * try/catch tables (and Morphe's instruction-tree writer) are untouched.
 */
@Suppress("unused")
val adFreePatch = bytecodePatch(
    name = "Remove ads & grant ad rewards",
    description = "Removes every ad (rewarded, interstitial, app-open, banner, MREC) and grants rewarded-ad rewards instantly — no video ever plays, and no ad can appear anywhere in the game."
) {
    compatibleWith(COMPATIBILITY_20MTD)

    dependsOn(pairipBypassPatch)

    execute {
        val pluginClass = mutableClassDefBy(MAX_PLUGIN)

        fun method(name: String, vararg params: String): MutableMethod =
            pluginClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.map(CharSequence::toString) == params.toList()
            } ?: error("MaxUnityPlugin.$name(${params.joinToString()}) not found — ad patch out of sync with 8.7")

        // ── One-shot latch field ───────────────────────────────────────────────
        // Timestamp (SystemClock.elapsedRealtime) of the last payout we emitted.
        // Needed because the reward event makes the game immediately call
        // loadRewardedAd again, which re-enters the helper ~20ms later: without
        // this guard a single click produced 445 reward events in 7.4 seconds.
        pluginClass.staticFields.add(
            ImmutableField(
                MAX_PLUGIN,
                "morpheLastRewardMs",
                "J",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                ImmutableLongEncodedValue(0L),
                null,
                null,
            ).toMutable()
        )

        // ── Injected helper ────────────────────────────────────────────────────
        // .registers 8 = 6 locals (v0-v5) + 2 params -> p0 = v6 (adUnitId),
        // p1 = v7 (code). Codes are formatIndex * 2 + eventKind:
        //   formatIndex 0 = REWARDED, 1 = REWARDED_INTERSTITIAL,
        //               2 = INTERSTITIAL, 3 = APP_OPEN
        //   eventKind   0 = loaded (onAdLoaded), 1 = shown (displayed/reward/hidden)
        // A single packed int keeps every call site inside its register budget
        // (`loadX` and `isXReady` only have two registers to work with).
        val emitHelper = ImmutableMethod(
            MAX_PLUGIN,
            "morpheEmitMaxAdEvent",
            listOf(
                ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ImmutableMethodParameter("I", null, null),
            ),
            "V",
            AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(12)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    # v0 = eventKind (0 loaded, 1 shown), v1 = formatIndex
                    and-int/lit8 v0, v11, 0x1
                    shr-int/lit8 v1, v11, 0x1

                    # v2 = MaxAdFormat for formatIndex
                    if-eqz v1, :fmt_rewarded
                    const/4 v5, 0x1
                    if-ne v1, v5, :fmt_rewarded_interstitial
                    const/4 v5, 0x2
                    if-ne v1, v5, :fmt_interstitial
                    sget-object v2, Lcom/applovin/mediation/MaxAdFormat;->APP_OPEN:Lcom/applovin/mediation/MaxAdFormat;
                    goto :fmt_ready
                    :fmt_rewarded
                    sget-object v2, Lcom/applovin/mediation/MaxAdFormat;->REWARDED:Lcom/applovin/mediation/MaxAdFormat;
                    goto :fmt_ready
                    :fmt_rewarded_interstitial
                    sget-object v2, Lcom/applovin/mediation/MaxAdFormat;->REWARDED_INTERSTITIAL:Lcom/applovin/mediation/MaxAdFormat;
                    goto :fmt_ready
                    :fmt_interstitial
                    sget-object v2, Lcom/applovin/mediation/MaxAdFormat;->INTERSTITIAL:Lcom/applovin/mediation/MaxAdFormat;
                    :fmt_ready

                    # v3 = synthetic MaxAd (kf implements MaxAd; kf.<init>(adUnitId, format, adUnitId))
                    new-instance v3, Lcom/applovin/impl/kf;
                    invoke-direct {v3, v10, v2, v10}, Lcom/applovin/impl/kf;-><init>(Ljava/lang/String;Lcom/applovin/mediation/MaxAdFormat;Ljava/lang/String;)V

                    # v4 = the SDK's own ad manager (lazy singleton, valid before initializeSdk)
                    invoke-static {}, Lcom/applovin/mediation/unity/MaxUnityPlugin;->getAdManager()Lcom/applovin/mediation/unity/MaxUnityAdManager;
                    move-result-object v4

                    # eventKind != 0 -> shown path. (This branch used to be `if-eqz`, which sent
                    # every load* code down the display path and every show* code down the
                    # loaded path — the whole reason a click emitted nothing but OnAdLoaded.)
                    if-nez v0, :emit_shown

                    invoke-virtual {v4, v3}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->onAdLoaded(Lcom/applovin/mediation/MaxAd;)V
                    return-void

                    :emit_shown
                    # ── Rate limit: one payout per user click ─────────────────
                    # The reward event makes C# call loadRewardedAd again within
                    # ~10-20ms, which lands back here. Suppress anything inside a
                    # 500ms window so the loop dies after the first payout (a
                    # suppressed reward emits no reward event, so C# never reloads
                    # again) while a real second click still pays. 3s was too long:
                    # it ate the user's next tap, so rewards only landed on the
                    # 2nd/3rd click.
                    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J
                    move-result-wide v6
                    sget-wide v8, Lcom/applovin/mediation/unity/MaxUnityPlugin;->morpheLastRewardMs:J
                    sub-long v8, v6, v8
                    const-wide/16 v6, 0x1f4
                    cmp-long v5, v8, v6
                    if-lez v5, :shown_suppressed
                    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J
                    move-result-wide v6
                    sput-wide v6, Lcom/applovin/mediation/unity/MaxUnityPlugin;->morpheLastRewardMs:J

                    invoke-virtual {v4, v3}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->onAdDisplayed(Lcom/applovin/mediation/MaxAd;)V

                    # Reward only for REWARDED (0) and REWARDED_INTERSTITIAL (1), i.e. formatIndex
                    # < 2; skip the reward for INTERSTITIAL (2) / APP_OPEN (3). NOTE: this branch
                    # previously used `if-lt`, which is the exact opposite — it *skipped* the reward
                    # for every rewarded format and fired it for interstitials. on-device log
                    # showed "displayed-ok / hidden-ok" with no reward.
                    # onUserRewarded NPEs on a null MaxReward, so always pass a real one.
                    const/4 v5, 0x2
                    if-ge v1, v5, :emit_hidden
                    const/4 v5, 0x1
                    invoke-static {v5, v10}, Lcom/applovin/impl/mediation/MaxRewardImpl;->create(ILjava/lang/String;)Lcom/applovin/mediation/MaxReward;
                    move-result-object v5
                    invoke-virtual {v4, v3, v5}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->onUserRewarded(Lcom/applovin/mediation/MaxAd;Lcom/applovin/mediation/MaxReward;)V

                    :emit_hidden
                    invoke-virtual {v4, v3}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->onAdHidden(Lcom/applovin/mediation/MaxAd;)V
                    return-void

                    :shown_suppressed
                    return-void
                """.trimIndent(),
            )
        }
        pluginClass.methods.add(emitHelper)

        // ── Fullscreen: show* -> synthetic displayed (+ reward) + hidden ───────
        // `.locals 1`, so v0 is a free local and p0 is the ad unit id.
        fun showBody(code: Int) = """
            const/4 v0, $code
            invoke-static {p0, v0}, Lcom/applovin/mediation/unity/MaxUnityPlugin;->morpheEmitMaxAdEvent(Ljava/lang/String;I)V
            return-void
        """.trimIndent()

        listOf(
            "showRewardedAd" to 0x1,
            "showRewardedInterstitialAd" to 0x3,
            "showInterstitial" to 0x5,
            "showAppOpenAd" to 0x7,
        ).forEach { (name, code) ->
            method(name, STRING, STRING, STRING).addInstructions(0, showBody(code))
        }

        // ── Fullscreen: load* -> synthetic loaded (so C# enables the ad button) ─
        fun loadBody(code: Int) = """
            const/4 v0, $code
            invoke-static {p0, v0}, Lcom/applovin/mediation/unity/MaxUnityPlugin;->morpheEmitMaxAdEvent(Ljava/lang/String;I)V
            return-void
        """.trimIndent()

        // Rewarded formats ALSO emit the full display + reward + hidden sequence here, not just
        // "loaded". 8.7 is a Unity IL2CPP title whose MAX C# wrapper never calls `showRewardedAd`
        // because the game's own config ships an empty `MaxAndroidRADId`
        // (`E Unity: No MAX Ads Ad Unit ID specified for: show rewarded ad`), and the guard lives
        // in C# where DEX cannot reach it. What *does* reach Java on every RSB click is the
        // retry-load: `Sauce.AdsManager.ShowRAD` checks `MaxSdk.IsRewardedAdReady(id)`, and on
        // false (always, with an empty id) it calls `MaxSdk.LoadRewardedAd(id)` instead. So the
        // load callback is the click signal — emitting the reward sequence there makes the click
        // pay out. Loads that fire without a pending reward callback (e.g. at startup) are
        // ignored by `Sauce.AdsManager`, which only forwards to the registered Action.
        fun loadAndGrantBody(code: Int) = """
            const/4 v0, $code
            invoke-static {p0, v0}, Lcom/applovin/mediation/unity/MaxUnityPlugin;->morpheEmitMaxAdEvent(Ljava/lang/String;I)V
            const/4 v0, ${code + 1}
            invoke-static {p0, v0}, Lcom/applovin/mediation/unity/MaxUnityPlugin;->morpheEmitMaxAdEvent(Ljava/lang/String;I)V
            return-void
        """.trimIndent()

        listOf(
            "loadRewardedAd" to 0x0,
            "loadRewardedInterstitialAd" to 0x2,
        ).forEach { (name, code) ->
            method(name, STRING).addInstructions(0, loadAndGrantBody(code))
        }

        listOf(
            "loadInterstitial" to 0x4,
            "loadAppOpenAd" to 0x6,
        ).forEach { (name, code) ->
            method(name, STRING).addInstructions(0, loadBody(code))
        }

        // ── Fullscreen: readiness always true ─────────────────────────────────
        listOf(
            "isRewardedAdReady",
            "isRewardedInterstitialAdReady",
            "isInterstitialReady",
            "isAppOpenAdReady",
        ).forEach { name ->
            method(name, STRING).addInstructions(0, "const/4 p0, 0x1\nreturn p0")
        }

        // ── Banner / MREC: layout getters report "no view" ────────────────────
        listOf("getBannerLayout", "getMRecLayout").forEach { name ->
            method(name, STRING).addInstructions(0, "const-string p0, \"\"\nreturn-object p0")
        }

        // Adaptive banner height -> 0 so no blank strip is reserved.
        method("getAdaptiveBannerHeight", "F").addInstructions(0, "const/4 p0, 0x0\nreturn p0")

        // ── Banner / MREC: neutralise the whole surface ───────────────────────
        // Not just createAdView: once no ad view exists,
        // MaxUnityAdManager.retrieveAdView returns null and
        // getAdViewLayout / destroyAdView / positionAdView would NPE.
        val noOps: List<Pair<String, List<String>>> = listOf(
            "createBanner" to listOf(STRING, STRING),
            "createBanner" to listOf(STRING, "F", "F"),
            "createMRec" to listOf(STRING, STRING),
            "createMRec" to listOf(STRING, "F", "F"),
            "loadBanner" to listOf(STRING),
            "loadMRec" to listOf(STRING),
            "showBanner" to listOf(STRING),
            "showMRec" to listOf(STRING),
            "hideBanner" to listOf(STRING),
            "hideMRec" to listOf(STRING),
            "destroyBanner" to listOf(STRING),
            "destroyMRec" to listOf(STRING),
            "startBannerAutoRefresh" to listOf(STRING),
            "stopBannerAutoRefresh" to listOf(STRING),
            "startMRecAutoRefresh" to listOf(STRING),
            "stopMRecAutoRefresh" to listOf(STRING),
            "updateBannerPosition" to listOf(STRING, "F", "F"),
            "updateBannerPosition" to listOf(STRING, STRING),
            "updateMRecPosition" to listOf(STRING, "F", "F"),
            "updateMRecPosition" to listOf(STRING, STRING),
            "setBannerBackgroundColor" to listOf(STRING, STRING),
            "setBannerCustomData" to listOf(STRING, STRING),
            "setBannerExtraParameter" to listOf(STRING, STRING, STRING),
            "setBannerLocalExtraParameter" to listOf(STRING, STRING, "Ljava/lang/Object;"),
            "setBannerLocalExtraParameterJson" to listOf(STRING, STRING, STRING),
            "setBannerPlacement" to listOf(STRING, STRING),
            "setBannerWidth" to listOf(STRING, "F"),
            "setMRecCustomData" to listOf(STRING, STRING),
            "setMRecExtraParameter" to listOf(STRING, STRING, STRING),
            "setMRecLocalExtraParameter" to listOf(STRING, STRING, "Ljava/lang/Object;"),
            "setMRecLocalExtraParameterJson" to listOf(STRING, STRING, STRING),
            "setMRecPlacement" to listOf(STRING, STRING),
        )

        noOps.forEach { (name, params) ->
            method(name, *params.toTypedArray()).addInstructions(0, "return-void")
        }
    }
}

private const val STRING = "Ljava/lang/String;"

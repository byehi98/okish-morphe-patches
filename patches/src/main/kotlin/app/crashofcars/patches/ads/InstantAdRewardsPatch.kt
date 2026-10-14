package app.crashofcars.patches.ads

import app.crashofcars.patches.shared.Constants.COMPATIBILITY_CRASH_OF_CARS
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable

/**
 * `showRewardedAd` ships with `.registers 5` (locals `v0`, `p0=v1`, `p1=v2`,
 * `p2=v3`, `p3=v4`). The replacement body needs four locals — `v0` (the
 * `MaxAd`), `v1` (event name, then the built `JSONObject`), `v2` and `v3` (the
 * key/value pair for `rewardLabel` and `rewardAmount`) — so the frame grows to
 * **8** (`v0..v3` locals, `p0=v4`, `p1=v5`, `p2=v6`, `p3=v7`).
 */
private const val SHOW_REWARDED_REGISTERS = 8

private const val PATCH_NAME = "Crash of Cars Instant Ad Rewards"

/**
 * Crash of Cars — Instant Ad Rewards
 * (com.notdoppler.crashofcars v1.9.34 / versionCode 1090034)
 *
 * Every "watch an ad for coins/gems/boosters" button pays out immediately, with
 * no video, no countdown and no network round trip.
 *
 * ## Where the rewarded flow enters Java
 *
 * Same AppLovin MAX 13.6.4 facade as [`crashOfCarsNoAdsPatch`], different format
 * and a different C# entry point (see
 * `analysis/com.notdoppler.crashofcars/notes/ad-patches.md` §1 and §0):
 *
 * ```
 * AdController::PlayAd                      RVA 0x23BCA94
 *   ├─ if (IAdNetwork.get_RewardVideoAvailable())          interface slot 0
 *   │    -> MaxAdNetwork::get_RewardVideoAvailable  RVA 0x23BD3AC
 *   │         -> MaxSdkAndroid::IsRewardedAdReady           RVA 0x3708778   ← A1
 *   └─ IAdNetwork.ShowRewardedVideo(...)                    interface slot 4
 *        -> MaxAdNetwork::ShowRewardedVideo       RVA 0x23BDCC4
 *             ├─ AdAudioUtils::SetAdShowing(true)  RVA 0x2105AAC   (before the call!)
 *             └─ MaxSdkAndroid::ShowRewardedAd    RVA 0x37088A4
 *                  └─ MaxUnityAdManager.showRewardedAd(String,String,String)V  ← A2
 *                       └─ MaxRewardedAd.showAd(…) → MaxFullscreenAdImpl.showAd(…)
 * ```
 *
 * `PlayAd` is reached from `UIGemPackWatchAd::OnPressed` (RVA `0x0208E2BC`),
 * `UIGemsPackWatchAd::OnPressed` (`0x02091738`), `UILivesShop::OnWatchAdPressed`
 * (`0x020D1940`) and `UITeamMatch::OnWatchVideoPressed` (`0x023CB344`).
 *
 * ## Both injections are mandatory — A1 gates A2
 *
 * `MaxSdkAndroid::ShowRewardedAd` re-checks `IsRewardedAdReady` at `+0x9C` and
 * bails (`tbz w0, #0, 0x3708A48`) when it is false, so **without A1 the A2 body
 * is never entered at all**. A1 also stops `AdController::PlayAd` from falling
 * through to `WaitingVideoLoading` (RVA `0x23BCE98`), the blocking "waiting for
 * video" popup that would otherwise sit on screen forever.
 *
 * ## Why the hidden event (#3) is not optional
 *
 * `MaxAdNetwork::ShowRewardedVideo` calls `AdAudioUtils::SetAdShowing(true)`
 * (RVA `0x2105AAC`) *before* crossing into Java. Only
 * `OnRewardedAdDismissedEvent` — driven by the Java `OnRewardedAdHiddenEvent` —
 * ever calls `SetAdShowing(false)` and sets `_closed = true`. Dropping it would
 * leave the game audio-muted after every reward and would break re-entry, since
 * `ShowRewardedVideo` relies on `_closed` to re-arm. This is the single most
 * likely silent regression of the whole patch, so it is called out here and
 * implemented explicitly.
 *
 * | # | Event forwarded | Lands in | Does |
 * |---|---|---|---|
 * | 1 | `OnRewardedAdDisplayedEvent` | `MaxAdNetwork::OnRewardedAdDisplayedEvent` (RVA `0x23BE3A8`) | `if (_onVideoOpened != null) _onVideoOpened()` — the game's ad-opened popup |
 * | 2 | `OnRewardedAdReceivedRewardEvent` (+ `rewardLabel` / `rewardAmount`) | `MaxAdNetwork::OnRewardedAdReceivedRewardEvent` (RVA `0x23BE44C`) | `if (!_closed && _onVideoReward != null) _onVideoReward(); _onVideoReward = null; _rewarded = true` → `AdController::OnVideoReward` (RVA `0x23BCDA0`) → `_onVideoAdReward?.Invoke(_wasDoubleReward)` — **the actual coin/gem grant** |
 * | 3 | `OnRewardedAdHiddenEvent` | `MaxAdNetwork::OnRewardedAdDismissedEvent` (RVA `0x23BE3E8`) | `LoadRewardedAd(); AdAudioUtils.SetAdShowing(false); if (_rewarded) _onVideoReward(); _closed = true` |
 *
 * The payload is built with the SDK's **own** helpers, so it is identical in
 * shape to what the SDK produces for a real reward at
 * `MaxUnityAdManager.smali:5036-5053` (`lambda$onUserRewarded$15$…`):
 *
 * - `getDefaultAdEventParameters(eventName, ad)` (`:1444`) → `getAdInfo(ad)` plus
 *   `putString(json, "name", eventName)`;
 * - `JsonUtils.putString(json, "rewardLabel", …)` (`:1968`, `public static`);
 * - `JsonUtils.putString(json, "rewardAmount", …)`;
 * - `forwardUnityEvent(json)` (`:954`, `private static`).
 *
 * `rewardLabel` = `"coins"` and `rewardAmount` = `"1"` are placeholders: the game
 * never reads them (verified — `AdRewardResults` is a name-heap entry at
 * `0x0215F7D` but is referenced by none of the `MaxAdNetwork` handlers; the grant
 * amount is decided in the caller's own UI code). They are written with the
 * production keys anyway so `MaxSdkBase.Reward` deserialises into exactly the
 * same shape.
 *
 * `MaxRewardedAd.showAd(...)` is never reached, so no ad is requested at display
 * time and no reward video is ever created.
 *
 * ## `getAd()` returning `null`
 *
 * Guarded with `if-eqz`, because `getDefaultAdEventParameters(name, null)` would
 * NPE inside `getAdInfo` on `MaxAd.getAdUnitId()`. On that path no event is
 * forwarded: the reward simply does not fire that time. It is a silent no-op, not
 * a crash, and it is unreachable in practice because `MaxAdNetwork.Init`
 * pre-loads the rewarded unit — which is what puts the entry in `adInfoMap` that
 * `getAd` reads.
 *
 * ## Deliberately NOT patched
 *
 * - **`AdController::OnVideoReward`** — forcing the grant directly would bypass
 *   `_rewarded` / `_closed` bookkeeping, `AdAudioUtils` and the analytics call,
 *   and would grant the wrong amount because the coin/gem maths lives in each
 *   caller's own UI code (`UIGemPackWatchAd`, `UIGemsPackWatchAd`, `UILivesShop`,
 *   `UITeamMatch`). Driving the SDK's own event gives all of that for free.
 * - **`MaxUnityAdManager.loadRewardedAd(String)V`** (`:6910`) — left in place so
 *   `adInfoMap` keeps being populated; see `crashOfCarsNoAdsPatch` for the same
 *   reasoning on the interstitial loader.
 * - **Ad removal** — a separate, independently switchable patch
 *   (`crashOfCarsNoAdsPatch`). The two are orthogonal: this one only ever
 *   *replaces* a rewarded video the player explicitly asked for.
 */
@Suppress("unused")
val crashOfCarsInstantAdRewardsPatch = bytecodePatch(
    name = "Crash of Cars Instant Ad Rewards",
    description = "Turns every \"watch a video\" offer into an instant payout. Coins, gems and boosters that the game offers in exchange for sitting through a rewarded advert are credited the moment you tap, with no video to play, no countdown and no waiting — and the offer stays repeatable afterwards, so you can claim it as often as you like. Works with no internet connection and the reward stays credited after restarting the game.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CRASH_OF_CARS)

    execute {
        // ── Fail loudly rather than ship an APK with a silently skipped hook ──
        MaxRewardedAdReadyFingerprint.matchAllOrNull()
            .requireExactlyOne(
                "MaxUnityAdManager.isRewardedAdReady(String)Z",
                PATCH_NAME
            )
        MaxRewardedAdShowFingerprint.matchAllOrNull()
            .requireExactlyOne(
                "MaxUnityAdManager.showRewardedAd(String,String,String)V",
                PATCH_NAME
            )

        // ═══ A1 — isRewardedAdReady → always true ═══
        //
        // `.registers 2` → locals `v0`, `p0=v1`. `const/4 v0, 0x1` / `return v0`
        // is register-free beyond the single local that already exists, so this
        // frame does **not** need to grow. The method has no exception table and
        // no labels, so a label-free replacement at index 0 is safe and the five
        // now-unreachable original instructions are not verified by ART.
        MaxRewardedAdReadyFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // ═══ A2 — showRewardedAd → displayed → reward → hidden, no ad ═══
        //
        // The shipped method is `.registers 5`: locals `v0`, `p0=v1`, `p1=v2`,
        // `p2=v3`, `p3=v4`. The body below needs `v0` (MaxAd), `v1` (event name,
        // then the built JSONObject) and `v2` / `v3` (the key/value pair for the
        // two `JsonUtils.putString` calls), so the frame must become
        // **`.registers 8`** with `p0=v4`, `p1=v5`, `p2=v6`, `p3=v7`.
        //
        // ⚠️ `addInstructions(0, …)` **cannot** do this: `registerCount` on
        // `MutableMethodImplementation` is a dexlib2 `final` field, so the new
        // instructions would be assembled against a 5-register frame, the Morphe
        // build would still be green, and the APK would fail dex verification on
        // the device. The frame is grown explicitly with
        // `cloneMutable(additionalRegisters = 3)`, the clone is swapped into the
        // class, and `requireRegisterCount` re-reads the result *from the class* to
        // prove the swap landed and the count is 8.
        val original = MaxRewardedAdShowFingerprint.method
        val expanded = original.cloneMutable(additionalRegisters = 3)
        MaxRewardedAdShowFingerprint.classDef.methods.apply {
            if (!remove(original)) {
                throw PatchException(
                    "$PATCH_NAME: could not remove the original showRewardedAd from" +
                        " $MANAGER — the frame expansion would silently not apply."
                )
            }
            add(expanded)
        }

        val patched = MaxRewardedAdShowFingerprint.classDef.requireRegisterCount(
            methodName = "showRewardedAd",
            parameterTypes = SHOW_PARAMETERS,
            expectedRegisters = SHOW_REWARDED_REGISTERS,
            patchName = PATCH_NAME
        )

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
            if-eqz v0, :morphe_reward_skipped

            # 1/3 — displayed
            #   -> MaxAdNetwork::OnRewardedAdDisplayedEvent (RVA 0x23BE3A8)
            #      if (_onVideoOpened != null) _onVideoOpened();
            const-string v1, "OnRewardedAdDisplayedEvent"
            invoke-direct {p0, v1, v0}, $MANAGER->getDefaultAdEventParameters(Ljava/lang/String;$MAX_AD;)$JSON_OBJECT;
            move-result-object v1
            # `forwardUnityEvent` is `private static` (:954), so this MUST be
            # invoke-static with ONE register. All 16 in-SDK call sites use
            # `invoke-static {vX}` (:1591, :1672, :3552, … :5053).
            invoke-static {v1}, $MANAGER->forwardUnityEvent($JSON_OBJECT;)V

            # 2/3 — reward  (THE GRANT)
            #   -> MaxAdNetwork::OnRewardedAdReceivedRewardEvent (RVA 0x23BE44C)
            #      if (!_closed && _onVideoReward != null) _onVideoReward();
            #      -> AdController::OnVideoReward (RVA 0x23BCDA0)
            #      -> _onVideoAdReward?.Invoke(_wasDoubleReward)
            const-string v1, "OnRewardedAdReceivedRewardEvent"
            invoke-direct {p0, v1, v0}, $MANAGER->getDefaultAdEventParameters(Ljava/lang/String;$MAX_AD;)$JSON_OBJECT;
            move-result-object v1

            # rewardLabel / rewardAmount are written with the SDK's own keys and
            # writer (:5036-5047 -> JsonUtils.putString, public static, :1968), so
            # MaxSdkBase.Reward deserialises the payload exactly as it would for a
            # real reward. The values are placeholders the game never reads.
            const-string v2, "rewardLabel"
            const-string v3, "coins"
            invoke-static {v1, v2, v3}, $JSON_UTILS;->putString($JSON_OBJECT;Ljava/lang/String;Ljava/lang/String;)V
            const-string v2, "rewardAmount"
            const-string v3, "1"
            invoke-static {v1, v2, v3}, $JSON_UTILS;->putString($JSON_OBJECT;Ljava/lang/String;Ljava/lang/String;)V

            invoke-static {v1}, $MANAGER->forwardUnityEvent($JSON_OBJECT;)V

            # 3/3 — hidden  (MANDATORY, NOT COSMETIC)
            #   -> MaxAdNetwork::OnRewardedAdDismissedEvent (RVA 0x23BE3E8)
            #      LoadRewardedAd();
            #      AdAudioUtils.SetAdShowing(false);   <-- un-mutes the game; without
            #          this the game stays muted forever, because
            #          MaxAdNetwork::ShowRewardedVideo set SetAdShowing(true) at
            #          RVA 0x2105AAC BEFORE crossing into Java.
            #      if (_rewarded) _onVideoReward();
            #      _closed = true;                      <-- re-arm for the next tap.
            const-string v1, "OnRewardedAdHiddenEvent"
            invoke-direct {p0, v1, v0}, $MANAGER->getDefaultAdEventParameters(Ljava/lang/String;$MAX_AD;)$JSON_OBJECT;
            move-result-object v1
            invoke-static {v1}, $MANAGER->forwardUnityEvent($JSON_OBJECT;)V

            # MaxRewardedAd.showAd(...) is NEVER reached: no ad is requested at
            # display time and no reward video is ever created.
            :morphe_reward_skipped
            return-void
        """.trimIndent())
    }
}

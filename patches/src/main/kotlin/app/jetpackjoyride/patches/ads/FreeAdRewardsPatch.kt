package app.jetpackjoyride.patches.ads

import app.jetpackjoyride.patches.shared.Constants.COMPATIBILITY_JETPACKJOYRIDE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable

private const val PATCH_NAME = "Free Ad Rewards"

private const val SHOW_REWARDED_AD = "ShowRewardedAd"
private const val LOAD_REWARDED_AD = "LoadRewardedAd"

/** `.registers 2` — `v0` free, `v1` = `p0` (the placement string). */
private const val SHOW_REWARDED_REGISTERS = 2

/** Bumped from the shipped `.registers 1`: `v0` = listener, `v1` = the `true`. */
private const val LOAD_REWARDED_REGISTERS = 2

/**
 * Jetpack Joyride — Free Ad Rewards
 * (com.halfbrick.jetpackjoyride v1.104.2 / versionCode 891646000)
 *
 * Every "watch a video for a reward" offer pays out the moment it is tapped:
 * no video, no countdown, no ad-network round trip, works offline. The offer
 * stays repeatable, because the native ad-space state is finalised exactly the
 * way a real advert would finalise it.
 *
 * ## Where the rewarded flow enters Java
 *
 * Everything below is `classes6.dex`. Halfbrick's glue is not obfuscated; the
 * only unusual-looking names are JNI-registered and therefore stable.
 *
 * ```
 * native libmortargame.so
 *   └─ Provider_IronsourceMediationBackend_Java::ShowRewardedAd(const char*)   [JNI upcall]
 *        └─ Provider_IronsourceMediationBackend.ShowRewardedAd(String)V   @424   ← hook A
 *             └─ runOnUiThread($$ExternalSyntheticLambda7)                 @434
 *                  └─ lambda$ShowRewardedAd$5(String)                      @967
 *                       └─ AdUnitCollection.ShowAd(String)                 @523   (shared!)
 *                            └─ RewardedAd.ShowAd(String)Z                 @274
 *                                 └─ LevelPlayRewardedAd.showAd(...)       @335
 *                                      └─ RewardedAd$1.onAdRewarded(...)   @377
 *                                           └─ RewardedAd$Listener.OnRewardEarned()V   @422
 *                                                └─ RewardedAdListener.OnRewardEarned()V @61
 *                                                     └─ INativeBridge.RewardEarned()
 *                                                          └─ ThreadSafeNativeBridge.RewardEarned()
 *                                                               └─ NativeGameLib.queueNativeThreadTask(...)
 *                                                                    └─ MortarNativeBridge.RewardEarned()  (JNI)
 *                                                                         └─ native …::SetRewardEarned()
 * ```
 *
 * The load path is a separate JNI upcall that only *advertises availability*:
 * `LoadRewardedAd()V` (`@314`) → `runOnUiThread(Lambda4)` → `AdUnitCollection.LoadAd()`
 * → `RewardedAd.LoadAd()` → SDK → `RewardedAd$Listener.OnAdLoaded(Z)`
 * → `INativeBridge.RewardedAdLoaded(Z)` → native `OnVideoAdLoaded(bool)`
 * (stores `2*flag` at `+0x68` and notifies every ad-space instance).
 *
 * ## Why a fake callback is honoured by native (this is the whole trick)
 *
 * The reward is granted **in native code**, not in Java — Java only delivers a
 * callback. `libmortargame.so` (x86_64, same APKM):
 *
 * ```
 * 0x144e600  Provider_IronsourceMediationBackend::ShowVideoAd(IAdSpaceInstance*)
 *   144e601: movb $0x1,0x1d(%rdi)          ; "an ad is showing" flag = 1
 *   144e61a: jmp  …_Java::ShowRewardedAd(char const*)   ; ← the upcall into the method we patch
 *
 * 0x144fc90  Provider_IronsourceMediationBackend::SetRewardEarned()
 *   144fc91: cmpb $0x0,0x1d(%rdi)          ; shown flag == 0 ?
 *   144fc95: je   …                        ;   → grant nothing
 *   144fc9a: movb $0x1,0x1c(%rdi)          ; rewardEarned = 1 (latched)
 *   144fcad: mov  $0x1,%esi
 *   144fcb2: call AdvertisingServiceBaseSingleAd::OnAdShown(bool)   ; ← the actual payout
 *   144fcb7: movw $0x0,0x1c(%rbx)          ; clear rewardEarned + shown flag
 * ```
 *
 * The `+0x1d` "shown" flag is written **before** the JNI upcall into Java, so
 * `RewardEarned()` arriving from our injected body passes the `0x1d` check and
 * the payout runs against a completely normal state machine. Nothing in native
 * or in Java validates that an advert was ever displayed. This is why the patch
 * needs no native modification at all.
 *
 * ## What is forced, and what is deliberately not touched
 *
 * * **A — `ShowRewardedAd(String)V`**: body replaced by
 *   `s_instance.mRewardedListener.OnRewardEarned()` → `IAdUnitListener.OnAdClosed(true)`
 *   → `return-void`. Order is mandatory (real completion order: `onAdRewarded`
 *   is the reward, `onAdClosed` finalises the ad space), and both hooks are the
 *   *app's own* listener implementations, so the callback chain down to
 *   `MortarNativeBridge` is byte-for-byte the production one:
 *   `RewardedAdListener` → `INativeBridge.RewardEarned()/RewardedAdClosed()`
 *   → `ThreadSafeNativeBridge` → `NativeGameLib.queueNativeThreadTask(...)`.
 *   `OnAdClosed(Z)` is declared on the super-interface [AD_UNIT_LISTENER], so the
 *   `invoke-interface` is verifier-safe even though `mRewardedListener` is typed
 *   as `RewardedAd$Listener`; `RewardedAdListener.OnAdClosed` ignores its boolean
 *   (`RewardedAdListener.smali:39-48`), exactly like the real close path.
 * * **B — `LoadRewardedAd()V`**: body replaced by
 *   `s_instance.mRewardedListener.OnAdLoaded(true)` → `return-void`, i.e. the
 *   game is told an advert is ready without LevelPlay ever being asked. This is
 *   what makes the Watch-Ad button available with no fill / no network. The real
 *   `runOnUiThread(Lambda4)` load is *removed* with the body, so nothing can
 *   later overwrite the faked state.
 *
 * Register budget (both methods are `static`):
 *  * A ships `.registers 2` → `p0 = v1`, `v0` free; the body uses `v0`/`v1` and
 *    the frame is **not** grown (`.registers` left at 2).
 *  * B ships `.registers 1` → only `v0` exists, so the frame is grown to
 *    **`.registers 2`** with `cloneMutable(additionalRegisters = 1)`. Widening is
 *    safe for this method because it has **no parameters**, so no parameter
 *    register moves; the old body (`v0` only) stays valid as dead code and
 *    `requireRegisterCount` proves the new frame from the class itself.
 *
 * ## Threading
 *
 * Both methods are JNI upcalls and therefore run on the **native game thread**;
 * the shipped bodies immediately hop to the UI thread via `runOnUiThread`. The
 * injected bodies call the listener directly on that native thread — which is
 * fine because every `INativeBridge` implementation
 * (`ThreadSafeNativeBridge`) does **not** touch JNI inline: it posts a Runnable
 * to `NativeGameLib.queueNativeThreadTask(...)`, which executes later on the
 * game's native thread while holding `NativeGameLib.GetSyncObj()`. The FIFO
 * queue preserves the `OnRewardEarned()` → `OnAdClosed(true)` order end-to-end,
 * and `s_instance` is guaranteed non-null whenever these entries are reachable
 * (`Initialise` assigns it at `Provider_IronsourceMediationBackend.smali:472`,
 * and the pre-existing `lambda$ShowRewardedAd$5` dereferences it unguarded).
 *
 * ## DO NOT (from `analysis/jetpackjoyride/notes/ad-rewards-and-remove-ads.md`)
 *
 *  * **Never** call `MortarNativeBridge.RewardEarned()` / `MortarNativeBridge.*`
 *    directly from this method — that is a *synchronous* JNI re-entry from
 *    inside a JNI upcall (`ShowVideoAd` is still mid-call), on the wrong thread,
 *    bypassing `GetSyncObj()`. Always go through the app's listener objects.
 *  * **Never** skip `OnAdClosed(true)` after `OnRewardEarned()` — it is what
 *    finalises native ad-space state (`QueueAdShownResult`, clears `+0x1c`/`+0x1d`);
 *    skipping it can leave the game's ad service stuck in "showing".
 *  * **Never** invert the order (`OnAdClosed` first): `SetRewardEarned` clears
 *    the reward flag at `144fcb7` once it has fired.
 *  * **Never** stub `AdUnitCollection.ShowAd` — it is the shared dispatcher for
 *    rewarded *and* interstitial units.
 *  * **Never** use `RewardedAd$1.onAdRewarded` or `IronsourceRewardedVideoListener`
 *    as the injection site: the first is only reached from a live SDK advert
 *    (exactly what is being bypassed), the second is dead code (`rg -l` finds no
 *    `new-instance` anywhere outside its own file).
 *  * **Never** fake `false` in `LoadRewardedAd` (the button would stay hidden) and
 *    never fake `true` *while* keeping the real `runOnUiThread` load — the real
 *    load would overwrite the state later.
 *
 * Pure DEX: no `rawResourcePatch`, no `dependsOn`, no extension. Independent of
 * [`jetpackJoyrideRemoveAdsPatch`] — `Remove Ads` touches `InterstitialAd`, this
 * patch touches `Provider_IronsourceMediationBackend`, so either may be applied
 * alone and there is no ordering constraint between them.
 */
@Suppress("unused")
val jetpackJoyrideFreeAdRewardsPatch = bytecodePatch(
    name = PATCH_NAME,
    description = "Every \"watch an ad\" offer in Jetpack Joyride pays out instantly — the " +
        "reward is claimed without playing any video, with no countdown and no network round " +
        "trip, and the offer stays repeatable. Works offline.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JETPACKJOYRIDE)

    execute {
        // ── Fail loudly rather than ship an APK with a silently skipped hook ──
        ShowRewardedAdFingerprint.matchAllOrNull()
            .requireExactlyOne("Provider_IronsourceMediationBackend.ShowRewardedAd(String)V", PATCH_NAME)
        LoadRewardedAdFingerprint.matchAllOrNull()
            .requireExactlyOne("Provider_IronsourceMediationBackend.LoadRewardedAd()V", PATCH_NAME)

        // ═══ A — ShowRewardedAd(String)V → reward + closed, no advert ═══
        //
        // `.registers 2` → `v0` local, `v1` = `p0` (placement string, read only by
        // the now-dead original body). The frame is *not* touched: the body below
        // fits in the existing two registers, so `addInstructions(0, …)` is enough
        // and the original `new-instance Lambda7` / `runOnUiThread` tail becomes
        // unreachable dead code (same style as the billing patch). No `.catch`
        // exists in this method, so no try range can end up dangling.
        ShowRewardedAdFingerprint.method.addInstructions(
            0,
            """
                # RewardedAd.Listener listener = Provider_IronsourceMediationBackend.s_instance.mRewardedListener;
                sget-object v0, $PROVIDER->s_instance:$PROVIDER
                iget-object v0, v0, $PROVIDER->mRewardedListener:$REWARDED_LISTENER

                # listener.OnRewardEarned()  — the real reward hop:
                #   RewardedAdListener.OnRewardEarned -> INativeBridge.RewardEarned()
                #   -> ThreadSafeNativeBridge -> MortarNativeBridge.RewardEarned() (JNI)
                #   -> native SetRewardEarned(), which passes because ShowVideoAd already
                #      set the +0x1d "shown" flag before this JNI upcall.
                invoke-interface {v0}, $REWARDED_LISTENER->OnRewardEarned()V

                # listener.OnAdClosed(true) — finalises the native ad-space state;
                # declared on the super-interface, so invoking it through
                # IAdUnitListener is verifier-safe. MUST stay second.
                const/4 v1, 0x1
                invoke-interface {v0, v1}, $AD_UNIT_LISTENER->OnAdClosed(Z)V

                return-void
            """.trimIndent()
        )

        // ═══ B — LoadRewardedAd()V → "an advert is ready", no LevelPlay ═══
        //
        // The shipped method is `.registers 1` — a single local, because it is
        // static and takes no parameters. The replacement needs `v0` (listener)
        // *and* `v1` (the boolean), so the frame must become `.registers 2`.
        //
        // ⚠️ `addInstructions(0, …)` alone cannot do this: the smali is assembled
        // against the existing 1-register frame, the build stays green, and the
        // APK fails dex verification on the device. The frame is grown explicitly
        // with `cloneMutable(additionalRegisters = 1)`, the clone is swapped into
        // the class, and `requireRegisterCount` re-reads the result from the class
        // to prove the swap landed. Widening a **parameterless** method cannot
        // move any parameter register, so the original `v0`-only body stays valid
        // (it is unreachable dead code below the injected `return-void`).
        val original = LoadRewardedAdFingerprint.method
        val expanded = original.cloneMutable(additionalRegisters = 1)

        LoadRewardedAdFingerprint.classDef.methods.apply {
            if (!remove(original)) {
                throw PatchException(
                    "$PATCH_NAME: could not remove the original $LOAD_REWARDED_AD from" +
                        " $PROVIDER — the frame expansion would silently not apply."
                )
            }
            add(expanded)
        }

        LoadRewardedAdFingerprint.classDef.requireRegisterCount(
            methodName = LOAD_REWARDED_AD,
            parameterTypes = emptyList(),
            expectedRegisters = LOAD_REWARDED_REGISTERS,
            patchName = PATCH_NAME
        ).addInstructions(
            0,
            """
                # load path: RewardedAdListener.OnAdLoaded(true)
                #   -> INativeBridge.RewardedAdLoaded(true)
                #   -> MortarNativeBridge.RewardedAdLoaded(Z) (JNI)
                #   -> native OnVideoAdLoaded(true): stores 2*flag at +0x68 and
                #      notifies every ad-space instance, i.e. "Watch Ad" is available.
                # No LoadAd() call is left in this method, so nothing can overwrite
                # the faked state afterwards.
                sget-object v0, $PROVIDER->s_instance:$PROVIDER
                iget-object v0, v0, $PROVIDER->mRewardedListener:$REWARDED_LISTENER
                const/4 v1, 0x1
                invoke-interface {v0, v1}, $AD_UNIT_LISTENER->OnAdLoaded(Z)V
                return-void
            """.trimIndent()
        )

        // Belt and braces: A must still have the frame its body assumes.
        ShowRewardedAdFingerprint.classDef.requireRegisterCount(
            methodName = SHOW_REWARDED_AD,
            parameterTypes = listOf("Ljava/lang/String;"),
            expectedRegisters = SHOW_REWARDED_REGISTERS,
            patchName = PATCH_NAME
        )
    }
}

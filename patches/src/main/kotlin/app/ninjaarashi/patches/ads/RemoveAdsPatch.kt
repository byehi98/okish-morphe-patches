package app.ninjaarashi.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.cloneMutable
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.ninjaarashi.patches.shared.Constants.COMPATIBILITY_NINJA_ARASHI
import com.android.tools.smali.dexlib2.Opcode

/**
 * Ninja Arashi 1.9.3 · **Remove Ads** — the game starts up already ad-free.
 *
 * ============================================================================
 * WHY THIS IS A DEX PATCH AND NOT A NATIVE ONE
 * ============================================================================
 * Ninja Arashi is Unity 6.2 / IL2CPP, so every ad decision is compiled into
 * `libil2cpp.so` and the only app class in DEX is `R`. That does *not* mean the DEX is
 * useless: the ad surface is gated entirely by **Unity PlayerPrefs**, and PlayerPrefs
 * is just `SharedPreferences`. So the cheapest, most durable place to intervene is the
 * launcher activity's `onCreate`, before Unity's player object even exists:
 *
 * ```
 * com.unity3d.player.UnityPlayerActivity.onCreate(Bundle)   classes3.dex, .registers 4
 *   requestWindowFeature(1)
 *   invoke-super ... Activity.onCreate(Bundle)   <-- inject here
 *   ... updateUnityCommandLineArguments ...
 *   new-instance UnityPlayerForActivityOrService   <-- Unity starts below this
 * ```
 *
 * Seeding `RemoveAds=1` (and `iap_noads=1`, the IAP receipt record) into
 * `shared_prefs/com.blackpanther.ninjaarashi.v2.playerprefs.xml` **before** the player
 * object is constructed means the very first ad request the game makes is already
 * suppressed — there is no first-launch banner/interstitial/app-open window.
 *
 * ============================================================================
 * WHAT READS THE FLAG (all verified in the shipping libs)
 * ============================================================================
 * ```
 * AdCreator.ShowAD                 arm64 0x13D6ED8 / armv7 0xA11ED4
 *     GetInt("RemoveAds"); cmp #1; b.eq -> returns before API.ShowInterstitial
 * AdCreator.ShowAD30s              arm64 0x13D6F9C / armv7 0xA11FB8   same check
 * MobileAdsManager.CanShowAds      arm64 0x1425A00 / armv7 0xA7B774
 *     !HasKey("RemoveAds") || GetInt("RemoveAds") == 0
 * InappCanvas.Refresh              arm64 0x13F5A60
 *     flips adsPrice -> adsPured when RemoveAds == 1 (the shop shows "owned")
 * ```
 * `iap_noads` is only the IAP receipt record; it is seeded for consistency so the
 * store UI and `IAPManager.TryRegrantNonConsumables()` agree with the ad-free state.
 * `TryRegrantNonConsumables()` (arm64 0x13F4BB8) only ever **sets** those flags to 1,
 * never clears them, so a seeded value cannot be revoked at startup.
 *
 * ============================================================================
 * REGISTER BUDGET
 * ============================================================================
 * The shipped method is `.registers 4` (locals `v0`,`v1`; `p0=v2`, `p1=v3`), and the
 * injected block needs three scratch registers. `MutableMethodImplementation.
 * registerCount` is a dexlib2 `final` field, so `addInstructions` alone cannot grow the
 * frame — the method is cloned with `cloneMutable(additionalRegisters = 3)` and the
 * clone is swapped into the class, giving `.registers 7` (`p0=v5`, `p1=v6`).
 *
 * `cloneMutable` preserves the original body's parameter slots by prepending
 * `move-object/from16 v2, p0` / `move/from16 v3, p1`, so **v2 and v3 still hold `this`
 * and the `Bundle`** for every instruction below the injection. The snippet therefore
 * uses `v0`, `v1` and `v4` only — all three are dead or brand new at that point
 * (`v0`/`v1` are reassigned by the original body before their next read, `v4` is a new
 * local), and the preserved parameters are never clobbered.
 *
 * The frame count is re-read **from the class** after the swap and the patch fails
 * loudly if it is not 7, rather than shipping a body that would `VerifyError` on device.
 */
private const val PATCH_NAME = "Ninja Arashi remove ads"
private const val ACTIVITY = "Lcom/unity3d/player/UnityPlayerActivity;"
private const val ON_CREATE_PARAMETERS = "Landroid/os/Bundle;"
private const val ON_CREATE_REGISTERS = 7

/**
 * `UnityPlayerActivity.onCreate(Bundle)` — the launcher activity itself.
 *
 * `definingClass` + `name` + parameters + return type are already exact; the
 * `requestWindowFeature` call is added purely so the match fails if the shipped Unity
 * player ever changes shape instead of silently hooking a different method.
 */
private object UnityPlayerOnCreateFingerprint : Fingerprint(
    definingClass = ACTIVITY,
    name = "onCreate",
    returnType = "V",
    parameters = listOf(ON_CREATE_PARAMETERS),
    filters = listOf(
        methodCall(definingClass = ACTIVITY, name = "requestWindowFeature")
    )
)

@Suppress("unused")
val ninjaArashiRemoveAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Makes the game start up ad-free: banners, interstitials and the app-open " +
        "ad never load, and the shop already shows \"Remove Ads\" as owned. The game's own " +
        "ad-free flag is set before Unity starts, so the setting survives restarts and " +
        "clearing app data simply re-applies it on the next launch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NINJA_ARASHI)

    execute {
        // ── Frame expansion: .registers 4 -> 7 ───────────────────────────────
        val original = UnityPlayerOnCreateFingerprint.method
        val expanded = original.cloneMutable(additionalRegisters = ON_CREATE_REGISTERS - 4)

        UnityPlayerOnCreateFingerprint.classDef.methods.apply {
            if (!remove(original)) {
                throw PatchException(
                    "$PATCH_NAME: could not remove the original UnityPlayerActivity.onCreate" +
                        " from the class — the frame expansion would silently not apply."
                )
            }
            add(expanded)
        }

        // Re-read from the class (not the local reference) so a swap that did not land
        // is caught here instead of as a VerifyError on device.
        val onCreate = UnityPlayerOnCreateFingerprint.classDef.methods.firstOrNull {
            it.name == "onCreate" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(ON_CREATE_PARAMETERS)
        } ?: throw PatchException(
            "$PATCH_NAME: UnityPlayerActivity.onCreate($ON_CREATE_PARAMETERS) is no longer" +
                " present after the frame expansion — the method swap did not land."
        )

        val registers = onCreate.implementation?.registerCount
        if (registers != ON_CREATE_REGISTERS) {
            throw PatchException(
                "$PATCH_NAME: UnityPlayerActivity.onCreate has .registers $registers," +
                    " expected $ON_CREATE_REGISTERS. The injected body addresses v4, which" +
                    " would not exist and would fail dex verification on device."
            )
        }

        // ── Injection point: immediately after invoke-super, before the player ──
        // `cloneMutable` prepended two `move` instructions, so the index is taken from
        // the expanded method, not the original.
        val superIndex = onCreate.indexOfFirstInstructionOrThrow(Opcode.INVOKE_SUPER)

        onCreate.addInstructions(superIndex + 1, """
            # ── Ad-free pre-seed ─────────────────────────────────────────────
            # SharedPreferences prefs = getSharedPreferences(
            #         getPackageName() + ".v2.playerprefs", 0);
            # prefs.edit().putInt("RemoveAds", 1).putInt("iap_noads", 1).commit();
            #
            # `getPackageName()` + ".v2.playerprefs" is exactly the file Unity uses for
            # this package (shared_prefs/com.blackpanther.ninjaarashi.v2.playerprefs.xml).
            # p0 = this Activity (also a Context); v0/v1/v4 are free at this point —
            # v2/v3 hold the preserved `this`/`Bundle` and must not be touched.
            invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v0
            const-string v1, ".v2.playerprefs"
            invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
            move-result-object v0
            invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v0

            # RemoveAds is what AdCreator.ShowAD / ShowAD30s / MobileAdsManager.CanShowAds
            # actually test; iap_noads is the IAP receipt record kept in sync.
            const-string v1, "RemoveAds"
            const/4 v4, 0x1
            invoke-interface {v0, v1, v4}, Landroid/content/SharedPreferences${'$'}Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences${'$'}Editor;
            const-string v1, "iap_noads"
            invoke-interface {v0, v1, v4}, Landroid/content/SharedPreferences${'$'}Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences${'$'}Editor;
            invoke-interface {v0}, Landroid/content/SharedPreferences${'$'}Editor;->commit()Z
        """.trimIndent())

        println(
            "$PATCH_NAME: seeded RemoveAds=1 / iap_noads=1 in UnityPlayerActivity.onCreate," +
                " before UnityPlayerForActivityOrService is constructed."
        )
    }
}

package app.injustice.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE

/**
 * Injustice: Gods Among Us — Ads Removal
 *
 * com.epicgames.virtuos.UnrealEngine3.UE3JavaIronSource.StartStaminaVideo()V is the
 * "watch ad to refill stamina" flow. Normally it plays a rewarded video through
 * IronSource/LevelPlay; when the ad completes the SDK reward callback posts
 * UE3JavaIronSource$1$1, whose run() calls
 *
 *   UE3JavaApp.NativeCallback_AwardTapjoyStamina()V   (confirmed smali:
 *   classes.dex com/epicgames/virtuos/UnrealEngine3/UE3JavaApp.smali:2236,
 *   .method public static native — invokes native INJUSTICEIOSGAME_AwardTapjoyStamina)
 *
 * which awards the stamina in native game code. We replace StartStaminaVideo's body with
 * that exact native call + return, so tapping "refill stamina" grants the refill
 * immediately and the ad never shows. This also removes the build's only ad surface
 * (the sole configured format is rewarded video — no banner/interstitial Java glue exists).
 *
 * Register budget: StartStaminaVideo has .registers 2 (v0 local + p0); the injected block
 * uses no registers (parameterless static call + return-void), so no expansion is needed.
 *
 * Risk: MEDIUM (per findings) — the award runs pure game-side logic (native
 * INJUSTICEIOSGAME_AwardTapjoyStamina); no "ad in progress" state is read back in Java.
 * Possible server save desync on WBID cloud-sync, as with all client-side grants.
 */
@Suppress("unused")
val injusticeAdsRemovalPatch = bytecodePatch(
    name = "Injustice Ads Removal",
    description = "Get stamina without watching ad Video.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        StartStaminaVideoFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;->NativeCallback_AwardTapjoyStamina()V
            return-void
        """.trimIndent())
    }
}

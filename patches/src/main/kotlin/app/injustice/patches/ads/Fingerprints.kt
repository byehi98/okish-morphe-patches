package app.injustice.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.epicgames.virtuos.UnrealEngine3.UE3JavaIronSource.StartStaminaVideo()V — public.
 *
 * The "Watch ad to refill stamina" entry point (native → JaveCallback_ShowWatchStaminaVideo
 * → this method). Normally it checks IronSource.isRewardedVideoAvailable(), stores the
 * STAMINA video type, then calls IronSource.showRewardedVideo("StaminaRewardedVideo") —
 * the real rewarded-ad flow. The actual stamina grant happens later in the SDK reward
 * callback (UE3JavaIronSource$1 → UE3JavaIronSource$1$1.run → UE3JavaApp.
 * NativeCallback_AwardTapjoyStamina()V).
 *
 * Confirmed smali: classes.dex com/epicgames/virtuos/UnrealEngine3/UE3JavaIronSource.smali:260
 * (.registers 2). We replace the whole body with a direct call to the same native award
 * function, so tapping the refill grants stamina instantly without playing any ad.
 *
 * Filters are in exact instruction order (verified against smali):
 *   IronSource.isRewardedVideoAvailable → "StaminaRewardedVideo" → IronSource.showRewardedVideo(String).
 * The "StaminaRewardedVideo" placement string uniquely selects this method over the sibling
 * StartDirectVideo (which uses "DefaultRewardedVideo", same surrounding structure).
 */
object StartStaminaVideoFingerprint : Fingerprint(
    definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaIronSource;",
    name = "StartStaminaVideo",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "isRewardedVideoAvailable",
        ),
        string("StaminaRewardedVideo"),
        methodCall(
            definingClass = "Lcom/ironsource/mediationsdk/IronSource;",
            name = "showRewardedVideo",
        ),
    )
)
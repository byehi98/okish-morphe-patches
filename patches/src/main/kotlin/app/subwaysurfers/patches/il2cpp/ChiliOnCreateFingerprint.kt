package app.subwaysurfers.patches.il2cpp

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * ChiliMultidexSupportActivity.onCreate(Bundle) — launcher activity of
 * com.kiloo.subwaysurf (LAUNCHER, exported, unityplayer.UnityActivity=true).
 *
 * Injection point for System.loadLibrary("SsIapGrant") (trigger patch),
 * mirroring ITD2's PikPokOnCreateFingerprint target.
 *
 * Confirmed smali: classes6/com/sybogames/chili/multidex/
 * ChiliMultidexSupportActivity.smali:152 (.registers 4). Body order:
 *   iget _vkQualityTest → VkQualityTest.CheckVkQuality →
 *   System.currentTimeMillis → sput EpochLaunchDateInMillis →
 *   iget mUnityPlayer → if-eqz → UnityPlayer.quit → const/4,iput →
 *   :cond_15 → super MessagingUnityPlayerActivity.onCreate → return-void
 *
 * Filters are the four invokes in exact instruction order (verified against
 * smali lines 160/165/185/198) and use stable SDK/dev names only — no
 * obfuscation. Register budget: v0 is written (iget-object) before it is
 * read at index 0, so the injected const-string v0 block at index 0 is safe.
 */
object ChiliOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/sybogames/chili/multidex/ChiliMultidexSupportActivity;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/sybogames/chili/multidex/VkQualityTest;", name = "CheckVkQuality"),
        methodCall(definingClass = "Ljava/lang/System;", name = "currentTimeMillis"),
        methodCall(definingClass = "Lcom/unity3d/player/UnityPlayer;", name = "quit"),
        methodCall(definingClass = "Lcom/google/firebase/MessagingUnityPlayerActivity;", name = "onCreate"),
    )
)

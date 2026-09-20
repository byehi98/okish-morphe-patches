package app.intothedead2.patches.il2cpp

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * T6 trigger — com.pikpok.PikPokUnityActivity.onCreate(Bundle).
 *
 * Same role as ubisoftpop's UnityOnCreateFingerprint target: the
 * `addInstructions(0, loadLibrary(...))` injection point for the companion
 * .so (rawResourcePatch + trigger pair).
 *
 * Confirmed smali: classes7/com/pikpok/PikPokUnityActivity.smali:213
 * (.registers 3). First instruction is the super call into
 * MessagingUnityPlayerActivity.onCreate; the body then builds `rootLayout`.
 * Filters are in exact instruction order (verified against smali) and use
 * stable dev/SDK names only — no obfuscation.
 */
object PikPokOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/pikpok/PikPokUnityActivity;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/google/firebase/MessagingUnityPlayerActivity;", name = "onCreate"),
        string("rootLayout"),
    )
)

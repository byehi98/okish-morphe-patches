package app.ubisoftpop.patches.il2cpp

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * UnityPlayerActivity.onCreate(Bundle) — v1.1.9 hardener-restructured layout.
 *
 * The commercial hardener rewired the original onCreate into:
 *   onCreate(Bundle) { qVJjNpZTozO(p1); KKVmaehEHIdkQfv(); return-void }
 *
 * - qVJjNpZTozO = original Unity onCreate body (ends with the unique
 *   invoke-virtual {p1}, UnityPlayer->requestFocus) — verified in smali.
 * - KKVmaehEHIdkQfv = hardener block (loadLibrary("GMFotUwvHx") + native
 *   verifier call LPYfMYTzXgBXhkhkCt;->a(Context)).
 *
 * Matching on the original-body method via its unique tail keeps the
 * fingerprint stable and self-documenting instead of trusting obfuscated
 * method names.
 */
object UnityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "qVJjNpZTozO",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/unity3d/player/UnityPlayer;", name = "requestFocus")
    )
)

/** Hardener init method: loadLibrary("GMFotUwvHx") + native verifier call. */
object HardenerInitFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "KKVmaehEHIdkQfv",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/System;", name = "loadLibrary")
    )
)

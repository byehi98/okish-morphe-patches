package app.swampattack2.patches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * UnityPlayerActivity.onCreate(Bundle) — smali-verified Java anchor for the
 * native currency engine (the ONLY entry point this IL2CPP title exposes:
 * game code lives entirely in libil2cpp.so, see notes/premium-bypass.md §7).
 *
 * - `smali/classes15/com/unity3d/player/UnityPlayerActivity.smali:91`
 *   `.method protected onCreate(Landroid/os/Bundle;)V`, `.registers 4`,
 *   super call `invoke-super {p0, p1}, Landroid/app/Activity;->onCreate`
 *   at line 100 (instruction index 2).
 * - Unity/SDK class names are never obfuscated → stable across builds.
 * - v0 is dead at index 0 (first use is `const/4 v0, 0x1` for
 *   requestWindowFeature), so the injected loadLibrary can reuse it.
 */
object UnityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Activity;", name = "onCreate")
    )
)

package app.intothedead2.patches.il2cpp

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.intothedead2.patches.shared.Constants.COMPATIBILITY_INTO_THE_DEAD_2

/**
 * Injects System.loadLibrary("Dr2IapGrant") as the first instruction of
 * PikPokUnityActivity.onCreate, so the native IAP-grant engine starts the
 * moment the app opens.
 *
 * Loading early is safe: JNI_OnLoad only spawns the worker thread, and the
 * worker itself probes for the libil2cpp.so mapping plus an unpack grace
 * period before touching any game memory (ubisoftpop lesson: packed
 * libil2cpp must not be touched before Unity's native init).
 *
 * Register budget: onCreate has .registers 3 (p0=this, p1=bundle, v0
 * local). The injected block only uses v0, which is not live at index 0.
 */
@Suppress("unused")
val nativeIapGrantTriggerPatch = bytecodePatch(
    name = "IAP Grant Trigger",
    description = "Wakes up that helper every time you open the game, so free purchases and subscriptions keep working.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INTO_THE_DEAD_2)

    execute {
        PikPokOnCreateFingerprint.method.addInstructions(
            0,
            """
            const-string v0, "Dr2IapGrant"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            """.trimIndent()
        )
    }
}

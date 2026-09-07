package app.ubisoftpop.patches.il2cpp

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.ubisoftpop.patches.shared.Constants.COMPATIBILITY_POP

/**
 * Injects System.loadLibrary("PopFullGame") as the first instruction of the
 * ORIGINAL Unity onCreate body (qVJjNpZTozO), which runs before the hardener
 * block executes in onCreate.
 *
 * Loading early means JNI_OnLoad spawns the engine worker immediately; the
 * worker itself waits 30 s for Unity's native init before touching il2cpp,
 * so an early load is safe (and matches the device-verified build flow).
 */
@Suppress("unused")
val fullGameUnlockTriggerPatch = bytecodePatch(
    name = "Prince of Persia: Full Game Unlock Trigger",
    description = "Tells the game to start that hidden helper the moment the app opens, so the unlock happens automatically.",
    default = true
) {
    compatibleWith(COMPATIBILITY_POP)

    execute {
        UnityOnCreateFingerprint.method.addInstructions(
            0,
            """
            const-string v0, "PopFullGame"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            """.trimIndent()
        )
    }
}

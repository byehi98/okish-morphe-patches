package app.subwaysurfers.patches.il2cpp

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.subwaysurfers.patches.shared.Constants.COMPATIBILITY_SUBWAY_SURFERS

/**
 * Injects System.loadLibrary("SsIapGrant") as the first instruction of
 * ChiliMultidexSupportActivity.onCreate, so the native IAP-grant engine
 * starts the moment the launcher activity opens.
 *
 * Loading early is safe: JNI_OnLoad only spawns the worker thread, and the
 * worker itself probes for the libil2cpp.so mapping plus an unpack grace
 * period before touching any game memory (ITD2/ubisoftpop lesson: packed
 * libil2cpp must not be touched before Unity's native init).
 *
 * Register budget: onCreate has .registers 4 (p0=this, p1=bundle, v0 local).
 * The injected block only uses v0, which is written (iget-object) before
 * first read at index 0 — not live at the injection point.
 */
@Suppress("unused")
val nativeIapGrantTriggerPatch = bytecodePatch(
    name = "IAP Grant Trigger",
    description = "Starts the free-purchase helper every time you open the game.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SUBWAY_SURFERS)

    execute {
        ChiliOnCreateFingerprint.method.addInstructions(
            0,
            """
            const-string v0, "SsIapGrant"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            """.trimIndent()
        )
    }
}

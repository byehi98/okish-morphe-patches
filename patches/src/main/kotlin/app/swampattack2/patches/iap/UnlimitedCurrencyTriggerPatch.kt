package app.swampattack2.patches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.swampattack2.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK_2

/**
 * Injects `System.loadLibrary("SwampUnlimited")` as the first instruction of
 * UnityPlayerActivity.onCreate — JNI_OnLoad then spawns the engine worker on
 * its own thread, which polls for libil2cpp.so and installs the currency and
 * ad-gating hooks (see UnlimitedCurrencyPatch for the target table). Loading
 * before super.onCreate means the hooks are in place well before the main
 * menu can read or write the wallet; the engine itself waits for the
 * runtime, so an early load is safe.
 */
@Suppress("unused")
val unlimitedCurrencyTriggerPatch = bytecodePatch(
    name = "Swamp Attack 2: Unlimited Currency Trigger",
    description = "Starts the Unlimited Currency helper when the game opens. Required for the Unlimited Currency Engine to work.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK_2)

    execute {
        UnityOnCreateFingerprint.method.addInstructions(
            0,
            """
            const-string v0, "SwampUnlimited"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            """.trimIndent()
        )
    }
}

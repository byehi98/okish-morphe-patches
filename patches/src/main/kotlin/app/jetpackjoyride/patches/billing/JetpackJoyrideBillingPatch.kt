package app.jetpackjoyride.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.jetpackjoyride.patches.shared.Constants.COMPATIBILITY_JETPACKJOYRIDE

@Suppress("unused")
val jetpackJoyrideBillingPatch = bytecodePatch(
    name = "Jetpack Joyride Billing Bypass",
    description = "Intercepts all in-app purchases and reports instant success to the game engine.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JETPACKJOYRIDE)

    execute {
        // RequestPurchaseInternal(String productId) is called on the UI thread whenever
        // the player initiates a purchase. We skip the billing flow entirely and call
        // SynchronizedOnPurchaseResult(productId, null, false, false, null) directly.
        //
        // SynchronizedOnPurchaseResult signature:
        //   (String productId, String token, boolean cancelled, boolean pending, String error)
        //
        // Passing: cancelled=false, pending=false, error=null → tells C++ engine "purchase OK".
        //
        // The method has .registers 3 (p0=productId, v0, v1) — we only use p0, v0, v1.
        RequestPurchaseInternalFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            const/4 v1, 0x0
            invoke-static {p0, v0, v0, v1, v0}, Lcom/halfbrick/mortar/GooglePlayPurchaseV2Service;->SynchronizedOnPurchaseResult(Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;)V
            return-void
        """.trimIndent())
    }
}

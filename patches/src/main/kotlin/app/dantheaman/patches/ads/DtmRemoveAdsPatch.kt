package app.dantheaman.patches.ads

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Dan the Man — Remove ads (interstitial + rewarded).
 *
 * All ad display funnels through the Halfbrick IronSource/LevelPlay bridge
 * `Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;` (called from
 * native libmortargame.so via JNI). The show flow is:
 *
 *   native → ShowInterstitialAd(String,String) / ShowRewardedAd(String,String)
 *     └─ runOnUiThread(new Provider_IronsourceMediationBackend$3/$5(...))
 *          └─ $3.run() → picks a ready interstitial → showAd(...)
 *          └─ $5.run() → picks a ready rewarded ad  → showAd(...)
 *
 * Interstitial: stub $3.run() with return-void. Native treats a never-closing
 * interstitial request with its normal pacing logic (verified on device).
 *
 * Rewarded: the engine BLOCKS until the native handshake completes. The real
 * listener (`IronSourceMediation_RewardedVideoListener`) defines the contract:
 *
 *   onAdRewarded(...) → synchronized(GetSyncObj()) { RewardEarned() }
 *   onAdClosed(...)   → synchronized(GetSyncObj()) { RewardedAdClosed() }
 *                       then LoadRewardedAds()
 *
 * A plain return-void stub fires neither callback → no reward + the flow hangs
 * (verified on device). So $5.run() is rewritten to replay the full successful
 * watch sequence inside the same native monitor:
 *
 *   RewardEarned() → RewardedAdClosed() → LoadRewardedAds()
 *
 * The game instantly receives the reward, its ad-flow state resolves exactly
 * as after a real watched video, and nothing is ever displayed. Straight-line
 * smali — no labels, no try-catch (a native callback throwing here is not a
 * realistic path; Halfbrick's own tail does the same calls).
 */
@Suppress("unused")
val dtmRemoveAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes all ads. Reward videos now give you the prize instantly — no watching needed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DTM)

    execute {
        // --- Interstitial: return-void stub (proven on device) ---
        val interstitialRun = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend\$3;",
            name = "run",
            returnType = "V",
            accessFlags = listOf(AccessFlags.PUBLIC),
            parameters = listOf(),
        ) {}

        val interstitialMethod = interstitialRun.method
            ?: throw PatchException("DTM remove ads: \$3.run() not found — app layout changed?")

        val interstitialBody = interstitialMethod.implementation?.instructions
            ?: throw PatchException("DTM remove ads: \$3.run() has no implementation")
        if (!interstitialBody.any { ins ->
                val ref = (ins as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference
                (ref as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)?.name?.startsWith("access") == true
            }
        ) {
            throw PatchException("DTM remove ads: \$3.run() does not look like an ad-show runnable (no access\$ bridge call)")
        }

        interstitialMethod.returnEarly()
        println("DTM remove ads: stubbed interstitial show runnable (\$3.run)")

        // --- Rewarded: replay the native reward handshake ---
        val rewardedRun = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend\$5;",
            name = "run",
            returnType = "V",
            accessFlags = listOf(AccessFlags.PUBLIC),
            parameters = listOf(),
        ) {}

        val rewardedMethod = rewardedRun.method
            ?: throw PatchException("DTM remove ads: \$5.run() not found — app layout changed?")

        val rewardedBody = rewardedMethod.implementation?.instructions
            ?: throw PatchException("DTM remove ads: \$5.run() has no implementation")
        if (!rewardedBody.any { ins ->
                val ref = (ins as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference
                (ref as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)?.name?.startsWith("access") == true
            }
        ) {
            throw PatchException("DTM remove ads: \$5.run() does not look like an ad-show runnable (no access\$ bridge call)")
        }

        // Prepend the full reward handshake ending in return-void: execution
        // never reaches the original body (it stays as harmless dead code —
        // ART verifies reachable code from entry, and this avoids dangling
        // try-block offsets from instruction removal).
        rewardedMethod.addInstructions(
            0,
            """
            invoke-static {}, Lcom/halfbrick/mortar/NativeGameLib;->GetSyncObj()Ljava/lang/Object;
            move-result-object v0
            monitor-enter v0
            invoke-static {}, Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;->RewardEarned()V
            invoke-static {}, Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;->RewardedAdClosed()V
            monitor-exit v0
            invoke-static {}, Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;->LoadRewardedAds()V
            return-void
            """.trimIndent()
        )
        println("DTM remove ads: rewrote rewarded show runnable (\$5.run) to instant-reward handshake")
    }
}

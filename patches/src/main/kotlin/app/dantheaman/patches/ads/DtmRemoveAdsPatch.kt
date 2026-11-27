package app.dantheaman.patches.ads

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

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
 *
 * 1.14.13 (LevelPlay migration) additionally requires the availability gate to
 * be bypassed: ShowRewardedAd() short-circuits to RewardedAdClosed() when no
 * rewarded inventory is loaded, so the patched $5.run() is never posted. That
 * method is therefore rewritten to always post $5, and isRewardedVideoLoaded()
 * is forced to return 1. Reward delivery no longer depends on ad inventory.
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

        // ── 3. REWARD AVAILABILITY: bypass the "no ad inventory" short-circuit ──
        //
        // 1.14.13 migrated the mediation bridge from ironSource to the LevelPlay
        // SDK (LaunchTestSuite now calls com.unity3d.mediation.LevelPlay). On
        // installs where no rewarded inventory loads (no GMS/Play services, no
        // network, or empty LevelPlay waterfall) the Java gate never reaches the
        // patched $5.run():
        //
        //   ShowRewardedAd(): if (s_rewardedAds == null || isEmpty)
        //                         -> synchronized { RewardedAdClosed() }   // no reward
        //                     else -> runOnUiThread(new $5(analyticsName))
        //   isRewardedVideoLoaded(): returns 0 while the map is empty
        //
        // In 1.14.12 inventory happened to be loaded on the test device, so the
        // $5 rewrite was enough. To make the reward independent of ad
        // inventory, ShowRewardedAd() is rewritten to always post the (patched)
        // $5 runnable, and isRewardedVideoLoaded() always reports "loaded" so
        // native/UI gates stay open.
        val showRewardedFingerprint = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;",
            name = "ShowRewardedAd",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            returnType = "V",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
        ) {}

        val showRewarded = showRewardedFingerprint.method
            ?: throw PatchException("DTM remove ads: ShowRewardedAd(String,String) not found — app layout changed?")
        val adsClassDef = showRewardedFingerprint.classDef
            ?: throw PatchException("DTM remove ads: Provider_IronsourceMediationBackend classdef not found")

        // p0/p1 = v1/v2 (static, 2 ref params, no locals). The whole method is
        // replaced (not edited) so the original try/catch monitor block cannot
        // be left dangling.
        val newShowRewarded = ImmutableMethod(
            "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;",
            "ShowRewardedAd",
            listOf(
                ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ImmutableMethodParameter("Ljava/lang/String;", null, null),
            ),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructions(
                0,
                """
                new-instance v0, Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend${'$'}5;
                invoke-direct {v0, p1}, Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend${'$'}5;-><init>(Ljava/lang/String;)V
                sget-object v1, Lcom/halfbrick/mortar/MortarGameActivity;->sActivity:Lcom/halfbrick/mortar/MortarGameActivity;
                invoke-virtual {v1, v0}, Lcom/halfbrick/mortar/MortarGameActivity;->runOnUiThread(Ljava/lang/Runnable;)V
                return-void
                """.trimIndent()
            )
        }
        adsClassDef.methods.remove(showRewarded)
        adsClassDef.methods.add(newShowRewarded)
        println("DTM remove ads: ShowRewardedAd always posts the instant-reward runnable (no inventory gate)")

        val rewardedLoadedFingerprint = object : Fingerprint(
            definingClass = "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;",
            name = "isRewardedVideoLoaded",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            returnType = "I",
            parameters = listOf(),
        ) {}

        val rewardedLoaded = rewardedLoadedFingerprint.method
            ?: throw PatchException("DTM remove ads: isRewardedVideoLoaded() not found — app layout changed?")

        val newRewardedLoaded = ImmutableMethod(
            "Lcom/halfbrick/mortar/Provider_IronsourceMediationBackend;",
            "isRewardedVideoLoaded",
            emptyList(),
            "I",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(1)
        ).toMutable().apply {
            addInstructions(
                0,
                """
                const/4 v0, 0x1
                return v0
                """.trimIndent()
            )
        }
        adsClassDef.methods.remove(rewardedLoaded)
        adsClassDef.methods.add(newRewardedLoaded)
        println("DTM remove ads: isRewardedVideoLoaded() forced to 1")
    }
}

package app.plagueinc.patches.unlock

import app.morphe.patches.all.misc.hex.hexPatch
import app.morphe.patcher.patch.rawResourcePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * The custom-scenario slot capacity is decided *inside the native engine*, not
 * in Java:
 *
 *   CustomScenariosManager::GetFreeSlotIndex()  -> MaxSlots()
 *   CustomScenariosManager::MaxSlots()          -> tier == 3 ? 8 : (tier == 2 ? 2 : 1)
 *   CustomScenariosManager::MaxDownloads()      -> tier == 3 ? INT_MAX : ...
 *
 * and `tier` comes from `UnlockManager::CustomScenarioSlotUnlockTier()`, which
 * only looks at the engine's own in-memory purchase registry:
 *
 *   WasPurchased("cust_scen_b") || WasPurchased("cust_scen_c") -> 3
 *   WasPurchased("cust_scen_a")                                -> 2
 *   otherwise                                                  -> 1
 *
 * The Java-side stubs in the other unlock patches only change what the UI
 * draws (8 slot tiles). They do not change the engine, so every download into
 * slot 2..8 is rejected by
 * `OnlineScenariosHandler::OnlineScenariosCanDownload` ->
 * `GetFreeSlotIndex() == -1` and `OnlineScenarios::PrepareToPlay` then returns
 * false *silently* - which is exactly the "download does nothing" symptom.
 *
 * This patch rewrites the first two instructions of
 * `UnlockManager::CustomScenarioSlotUnlockTier` (vaddr 0x939898, which is also
 * the file offset since .text is mapped 1:1) into:
 *
 *   mov w0, #3
 *   ret
 *   nop
 *   nop
 *
 * so the engine always reports the top slot tier. `MaxSlots()` therefore
 * returns 8 and `MaxDownloads()` returns INT_MAX.
 */
@Suppress("unused")
val plagueIncNativeCustomScenarioSlotsPatch = rawResourcePatch(
    name = "Plague Inc. Native Custom Scenario Slots",
    description = "Forces the native engine to report custom scenario slot tier 3 (8 slots, unlimited downloads).",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    dependsOn(
        hexPatch(block = {
            // _ZNK13UnlockManager28CustomScenarioSlotUnlockTierEv @ 0x939898
            //   stp x29, x30, [sp, #-0x10]!
            //   mov x29, sp
            //   adrp x1, 0x2b8000        ; "cust_scen_b"
            //   add x1, x1, #0xa9e
            "fd 7b bf a9 fd 03 00 91 e1 cb ff f0 21 78 2a 91" asPatternTo
            //   mov w0, #3
            //   ret
            //   nop
            //   nop
            "60 00 80 52 c0 03 5f d6 1f 20 03 d5 1f 20 03 d5" inFile
                "lib/arm64-v8a/libPlagueIncAndroidNative.so"
        })
    )
}

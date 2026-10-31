package app.plagueinc.patches.unlock

import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

@Suppress("unused")
val plagueIncCustomScenarioUpgradesPatch = bytecodePatch(
    name = "Plague Inc. Custom Scenario Upgrades",
    description = "Gives 8 custom scenario save slots and unlimited downloads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        val unlocksClass = UnlocksClassFingerprint.classDef as MutableClass
        convertNativeUnlockMethodToIntStub(unlocksClass, "getCustomScenarioUnlockTier", 3)

        val scenariosClass = ScenariosDownloadsFingerprint.classDef as MutableClass
        convertNativeUnlockMethodToIntStub(scenariosClass, "getDownloadsAvailable", Int.MAX_VALUE)
        convertNativeUnlockMethodToIntStub(scenariosClass, "getHoursTillDownloadReset", 0)

        // getFreeDownloadSlotIndex: replace native with a loop that finds the
        // first slot whose scenario id is empty.
        val original = scenariosClass.methods.first { it.name == "getFreeDownloadSlotIndex" }
        val stub = MutableMethod(
            ImmutableMethod(
                scenariosClass.type,
                "getFreeDownloadSlotIndex",
                original.parameters,
                "I",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(4)
            )
        ).apply {
            addInstructions(0, """
                const/4 v0, 0x0
                :slot_loop
                const/16 v1, 0x8
                if-ge v0, v1, :slot_none
                invoke-static {v0}, Lcom/miniclip/plagueinc/jni/Scenarios;->getScenarioFromSlot(I)Ljava/lang/String;
                move-result-object v1
                invoke-static {v1}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :slot_found
                add-int/lit8 v0, v0, 0x1
                goto :slot_loop
                :slot_found
                return v0
                :slot_none
                const/4 v0, -0x1
                return v0
            """)
        }
        scenariosClass.methods.remove(original)
        scenariosClass.methods.add(stub)
    }
}
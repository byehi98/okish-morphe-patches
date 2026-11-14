package app.swiftbackup.patches.backup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.swiftbackup.patches.cloud.swiftBackupLocalCloudMetadataPatch
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction30t
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val SETTINGS_BUNDLE = "Lapp/morphe/extension/swiftbackup/SettingsBundle;"
private const val GSON = "Lcom/google/gson/a;"
private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"
private const val SWIFT_APP = "Lorg/swiftapps/swiftbackup/SwiftApp;"
private const val SWIFT_APP_COMPANION = "Lorg/swiftapps/swiftbackup/SwiftApp\$Companion;"
private const val ALARM_RECEIVER = "Lorg/swiftapps/swiftbackup/jobs/AlarmReceiver;"

// jl4.a(Object, Object)Z — the null-safe equality the app uses to compare a Boolean with TRUE.
private const val EQUALITY = "Ljl4;"

/**
 * Extends the anonymous settings export/import with everything else the local account owns:
 * the local Realtime Database nodes (labels, configs, schedules, favorites/blacklist and the whole
 * cloud_v1 catalog), the cache files backing them and the UI preferences that are not part of
 * AppSettings. The extra payload is stored as raw JSON under "morpheLocalAccount", so it is lossless
 * and is ignored by an unpatched install.
 *
 * Flow (see analysis/swiftbackup/notes/local-account-backup-plan.md):
 *  - export: g48 decorates the Gson string before it is written to the Uri,
 *  - import: h48 applies the extras before Gson parses SettingsData, i.e. before restore() restarts
 *    the app; cache files, prefs and the local database are flushed first, and the alarm re-arm
 *    bridge runs last.
 */
@Suppress("unused")
val swiftBackupLocalAccountBackupPatch = bytecodePatch(
    name = "Swift Backup Local Account Backup",
    description = "Extends Export/Import settings with labels, configs, schedules, the cloud backup catalog and UI preferences so the local account survives a reinstall.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)
    dependsOn(swiftBackupLocalCloudMetadataPatch)
    extendWith("extensions/swiftbackup.mpe")

    execute {
        val exportMethod = SettingsExportFingerprint.method
        val importMethod = SettingsImportFingerprint.method
        val exportClass = SettingsExportFingerprint.classDef
        val helper = exportClass.type

        // Classes the bridges call into. classDefBy throws when the build does not have them.
        classDefBy(SWIFT_APP)
        classDefBy(SWIFT_APP_COMPANION)
        classDefBy(ALARM_RECEIVER)
        classDefBy("Lkk3;")
        classDefBy("Ltd1;")
        classDefBy("Laf1;")

        fun newStaticMethod(
            name: String,
            parameters: List<String>,
            returnType: String,
            registerCount: Int
        ): MutableMethod = MutableMethod(
            ImmutableMethod(
                helper,
                name,
                parameters.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(registerCount)
            )
        )

        /** Adds a static helper whose body is emitted verbatim. */
        fun addStaticHelper(
            name: String,
            parameters: List<String>,
            returnType: String,
            registerCount: Int,
            smali: String
        ) = exportClass.methods.add(
            newStaticMethod(name, parameters, returnType, registerCount)
                .apply { addInstructionsWithLabels(0, smali.trimIndent()) }
        )

        /**
         * Adds a static helper where [body] runs inside `catch (Exception)`; [handler] runs when it
         * throws. [prelude] and [tail] stay outside the guarded region, so the catch handler can rely
         * on registers initialised by the prelude.
         */
        fun addCatchingHelper(
            name: String,
            parameters: List<String>,
            returnType: String,
            registerCount: Int,
            prelude: String,
            body: String,
            tail: String,
            handler: String
        ) = exportClass.methods.add(
            newStaticMethod(name, parameters, returnType, registerCount).apply {
                val impl = implementation!!
                if (prelude.isNotBlank()) addInstructionsWithLabels(0, prelude.trimIndent())
                val tryStart = impl.instructions.size
                addInstructionsWithLabels(tryStart, body.trimIndent())
                val tryEnd = impl.instructions.size
                addInstructionsWithLabels(tryEnd, tail.trimIndent())
                val handlerStart = impl.instructions.size
                addInstructionsWithLabels(handlerStart, handler.trimIndent())
                impl.addCatch(
                    "Ljava/lang/Exception;",
                    impl.newLabelForIndex(tryStart),
                    impl.newLabelForIndex(tryEnd),
                    impl.newLabelForIndex(handlerStart)
                )
            }
        )

        // Export-time context: uid, cloud dir, cloud constant and active tag. Must never throw, so the
        // object is built outside the guarded region and returned even when the lookups fail.
        // kb5.b() is the null-checked form of kk3.i() (the latter exits the process when signed out).
        addCatchingHelper(
            name = "morpheCloudContextJson",
            parameters = listOf(),
            returnType = "Ljava/lang/String;",
            registerCount = 4,
            prelude = """
                new-instance v0, Lorg/json/JSONObject;
                invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
            """,
            body = """
                const-string v1, "uid"
                invoke-static {}, Lkb5;->b()Lorg/swiftapps/swiftbackup/anonymous/MFirebaseUser;
                move-result-object v2
                if-eqz v2, :no_uid
                invoke-static {}, Lkk3;->i()Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
                :no_uid
                const-string v1, "cloudDir"
                const-string v2, "morphe"
                invoke-static {v2}, Ltd1;->j(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
                const-string v1, "cloudConst"
                invoke-static {}, Ltd1;->g()Laf1;
                move-result-object v2
                invoke-virtual {v2}, Laf1;->getConstant()Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
                const-string v1, "activeTag"
                invoke-static {}, Ltd1;->f()Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
            """,
            tail = """
                invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
            handler = """
                move-exception v1
                invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
        )

        // Returns the original JSON untouched when anything inside the extension fails.
        addStaticHelper(
            name = "morpheDecorate",
            parameters = listOf("Ljava/lang/String;"),
            returnType = "Ljava/lang/String;",
            registerCount = 3,
            smali = """
                invoke-static {}, ${helper}->morpheCloudContextJson()Ljava/lang/String;
                move-result-object v0
                invoke-static {v2, v0}, ${SETTINGS_BUNDLE}->decorate(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
        )

        // Applies the extras; re-arms the alarms when schedules were restored.
        addStaticHelper(
            name = "morpheApply",
            parameters = listOf("Ljava/lang/String;"),
            returnType = "V",
            registerCount = 3,
            smali = """
                invoke-static {}, ${helper}->morpheCloudContextJson()Ljava/lang/String;
                move-result-object v0
                invoke-static {v2, v0}, ${SETTINGS_BUNDLE}->apply(Ljava/lang/String;Ljava/lang/String;)Z
                move-result v1
                if-eqz v1, :no_rearm
                invoke-static {}, ${helper}->morpheRearmSchedules()V
                :no_rearm
                return-void
            """
        )

        // AlarmReceiver.c(): sets KEY_SCHEDULE_ENABLED, schedules the exact alarm and enables
        // BootReceiver. Only reachable when schedules were restored; never throws.
        addCatchingHelper(
            name = "morpheRearmSchedules",
            parameters = listOf(),
            returnType = "V",
            registerCount = 2,
            prelude = "",
            body = """
                invoke-static {}, ${SWIFT_APP_COMPANION}->a()${SWIFT_APP}
                move-result-object v0
                invoke-virtual {v0}, ${SWIFT_APP}->e()${ALARM_RECEIVER}
                move-result-object v0
                invoke-virtual {v0}, ${ALARM_RECEIVER}->c()V
            """,
            tail = """
                return-void
            """,
            handler = """
                move-exception v0
                return-void
            """
        )

        // Export: right after Gson produced the settings string, before it is written to the Uri.
        val gsonToJsonIndex = exportMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
                it.definingClass == GSON && it.name == "i" && it.parameterTypes.size == 1
            } == true
        }
        val exportStringIndex = exportMethod.indexOfFirstInstructionOrThrow(gsonToJsonIndex, Opcode.MOVE_RESULT_OBJECT)
        val exportStringRegister = exportMethod.getInstruction<OneRegisterInstruction>(exportStringIndex).registerA
        exportMethod.addInstructions(
            exportStringIndex + 1,
            """
                invoke-static {v$exportStringRegister}, ${helper}->morpheDecorate(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$exportStringRegister
            """.trimIndent()
        )

        // Import: right after the raw JSON was read into a register, before Gson parses SettingsData
        // (restore() at that point restarts the app, so the extras must already be applied).
        val gsonFromJsonIndex = importMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let { reference ->
                reference.definingClass == GSON && reference.name == "c" &&
                    reference.parameterTypes.size == 2 &&
                    reference.parameterTypes[0].toString() == "Ljava/lang/String;"
            } == true
        }
        val rawJsonIndex = run {
            for (index in gsonFromJsonIndex - 1 downTo 1) {
                if (importMethod.getInstruction(index).opcode != Opcode.MOVE_RESULT_OBJECT) continue
                val previous = importMethod.getInstruction(index - 1)
                if (previous.opcode != Opcode.INVOKE_VIRTUAL) continue
                val reference = previous.getReference<MethodReference>() ?: continue
                if (reference.definingClass == STRING_BUILDER && reference.name == "toString") return@run index
            }
            error("SwiftBackup: no StringBuilder.toString() result found before the settings Gson parse")
        }
        val rawJsonRegister = importMethod.getInstruction<OneRegisterInstruction>(rawJsonIndex).registerA
        // invoke-virtual {receiver, json, class} → the JSON string is the first argument (registerD).
        val gsonCall = importMethod.getInstruction<FiveRegisterInstruction>(gsonFromJsonIndex)
        check(gsonCall.registerD == rawJsonRegister) {
            "SwiftBackup: settings Gson parse reads v${gsonCall.registerD} but the JSON was read into v$rawJsonRegister"
        }
        importMethod.addInstructions(
            rawJsonIndex + 1,
            "invoke-static {v$rawJsonRegister}, ${helper}->morpheApply(Ljava/lang/String;)V"
        )

        // SettingsActivity.onCreatePreferences gates the export/import entries behind the same
        // isAnonymous check. Dropping the branch makes both Preference.D(true) calls run for every
        // account type.
        val settingsMethod = SettingsExportImportVisibilityFingerprint.method
        settingsMethod.forceFallthrough(settingsMethod.anonymousGateIndex())
        println("SwiftBackup: Export/Import settings now visible for every account type")

        // The click handler runs the same check twice per branch, before it launches the
        // CREATE_DOCUMENT / OPEN_DOCUMENT picker. Force all four gates so signed-in accounts can
        // actually export/import.
        val clickMethod = SettingsExportImportClickFingerprint.method
        val clickGates = clickMethod.anonymousGateIndices()
        check(clickGates.size == 4) {
            "SwiftBackup: expected 4 export/import click gates in nl7.e, found ${clickGates.size}"
        }
        clickGates.sortedDescending().forEach { clickMethod.forceBranch(it) }
        println("SwiftBackup: Export/Import preference clicks no longer bail out for signed-in accounts")

        println("SwiftBackup: settings export/import extended with local account data (rtdb, caches, prefs, schedule re-arm)")
    }
}

/** True for the `jl4.a(Object, Object)Z` comparison of the anonymous flag with TRUE. */
private fun Instruction.isAnonymousGate(): Boolean =
    opcode == Opcode.INVOKE_STATIC && getReference<MethodReference>()?.let {
        it.definingClass == EQUALITY && it.name == "a" && it.parameterTypes.size == 2
    } == true

/**
 * Index of the conditional branch that consumes `jl4.a(isAnonymous(), TRUE)`.
 *
 * The comparison is matched rather than the isAnonymous() call: m() contains a second jl4.a
 * comparison (for the dynamic_colors switch) that is not a gate. Every real gate has the exact
 * shape `invoke-static jl4.a(..)` → `move-result` → `if-eqz`/`if-nez`.
 */
private fun MutableMethod.anonymousGateIndex(): Int =
    anonymousGateIndices().firstOrNull() ?: error("SwiftBackup: no anonymous gate found")

/** Indices of every conditional branch that consumes `jl4.a(..., TRUE)`. */
private fun MutableMethod.anonymousGateIndices(): List<Int> {
    val instructionList = instructions
    return buildList {
        for (index in 0 until instructionList.size - 2) {
            if (!instructionList[index].isAnonymousGate()) continue
            if (instructionList[index + 1].opcode != Opcode.MOVE_RESULT) continue
            val branch = instructionList[index + 2].opcode
            if (branch == Opcode.IF_EQZ || branch == Opcode.IF_NEZ) add(index + 2)
        }
    }
}

/** Forces fall-through (the branch is never taken). */
private fun MutableMethod.forceFallthrough(index: Int) {
    replaceInstruction(index, BuilderInstruction10x(Opcode.NOP))
}

/**
 * Forces the branch to be taken by replacing it with an unconditional goto.
 *
 * The goto has to be built against the branch's own label: a `replaceInstruction(index, "goto :x")`
 * snippet cannot resolve a label that lives in the target method.
 */
@Suppress("unused")
private fun MutableMethod.forceBranch(index: Int) {
    val target = getInstruction<BuilderOffsetInstruction>(index).target
    replaceInstruction(index, BuilderInstruction30t(Opcode.GOTO_32, target))
}

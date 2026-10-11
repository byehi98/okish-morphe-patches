package app.swiftbackup.patches.cloud

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val LOCAL_DB = "Lapp/morphe/extension/swiftbackup/LocalDb;"
private const val INIT_METHOD = "morpheLocalDbInit"

/**
 * Cloud-backup metadata lives in the vendor's Firebase Realtime Database, whose rules reject
 * accounts without a server-verified purchase ("Permission denied" on every read/write under users/<uid>).
 * This keeps the database client permanently offline and backs it with a JSON file instead:
 * the tree is seeded as server data on Repo init and every outgoing write is stored locally,
 * echoed back as a server overwrite and acked. Metadata is device-local (not synced, lost on data wipe).
 */
@Suppress("unused")
val swiftBackupLocalCloudMetadataPatch = bytecodePatch(
    name = "Swift Backup Local Cloud Metadata",
    description = "Makes cloud backups work by keeping their metadata on the device instead of the Swift Backup server.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)

    extendWith("extensions/swiftbackup.mpe")

    execute {
        // wz5.e: replace the network put/merge. 3 locals + p0..p5; p1 is reused once its value is consumed.
        PersistentConnectionPutFingerprint.method.addInstructionsWithLabels(0, """
            invoke-static {p1, p2, p3}, $LOCAL_DB->write(Ljava/lang/String;Ljava/util/List;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            iget-object v1, p0, Lwz5;->a:Lwj6;
            new-instance v2, Lgy5;
            invoke-direct {v2, p2}, Lgy5;-><init>(Ljava/util/List;)V
            sget-object p1, Lqv2;->e:Lqv2;
            invoke-static {v0, p1}, Lxh8;->a(Ljava/lang/Object;Lqn5;)Lqn5;
            move-result-object v0
            iget-object p1, v1, Lwj6;->n:Lbv7;
            invoke-virtual {p1, v2, v0}, Lbv7;->g(Lgy5;Lqn5;)Ljava/util/List;
            move-result-object v0
            invoke-virtual {v1, v0}, Lwj6;->j(Ljava/util/List;)V
            if-eqz p5, :no_callback
            invoke-static {p5}, $LOCAL_DB->ack(Ljava/lang/Object;)Ljava/lang/Runnable;
            move-result-object v0
            invoke-virtual {v1, v0}, Lwj6;->n(Ljava/lang/Runnable;)V
            :no_callback
            return-void
        """.trimIndent())

        // Static helper on wz5: interrupt the connection for good, pin a root listener so the
        // server sync tree keeps the whole tree, then seed it from the local file.
        // 5 locals + 1 param: p0 = v5.
        val connectionClass = PersistentConnectionPutFingerprint.classDef
        connectionClass.methods.add(
            MutableMethod(
                ImmutableMethod(
                    connectionClass.type,
                    INIT_METHOD,
                    listOf(ImmutableMethodParameter("Lwj6;", null, null)),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(6)
                )
            ).apply {
                addInstructions(0, """
                    iget-object v0, v5, Lwj6;->c:Lwz5;
                    const-string v1, "morphe-local-db"
                    invoke-virtual {v0, v1}, Lwz5;->c(Ljava/lang/String;)V
                    sget-object v0, Lgy5;->d:Lgy5;
                    new-instance v1, Lqc6;
                    sget-object v2, Loc6;->f:Loc6;
                    invoke-direct {v1, v0, v2}, Lqc6;-><init>(Lgy5;Loc6;)V
                    const-class v2, Laj8;
                    invoke-static {v2}, $LOCAL_DB->noopListener(Ljava/lang/Class;)Ljava/lang/Object;
                    move-result-object v2
                    check-cast v2, Laj8;
                    new-instance v3, Lbj8;
                    invoke-direct {v3, v5, v2, v1}, Lbj8;-><init>(Lwj6;Laj8;Lqc6;)V
                    iget-object v4, v5, Lwj6;->n:Lbv7;
                    const/4 v1, 0x0
                    invoke-virtual {v4, v3, v1}, Lbv7;->d(Lez2;Z)Ljava/util/List;
                    move-result-object v1
                    invoke-virtual {v5, v1}, Lwj6;->j(Ljava/util/List;)V
                    invoke-static {}, $LOCAL_DB->load()Ljava/lang/Object;
                    move-result-object v1
                    sget-object v2, Lqv2;->e:Lqv2;
                    invoke-static {v1, v2}, Lxh8;->a(Ljava/lang/Object;Lqn5;)Lqn5;
                    move-result-object v1
                    invoke-virtual {v4, v0, v1}, Lbv7;->g(Lgy5;Lqn5;)Ljava/util/List;
                    move-result-object v0
                    invoke-virtual {v5, v0}, Lwj6;->j(Ljava/util/List;)V
                    return-void
                """.trimIndent())
            }
        )

        val initMethod = RepoDeferredInitFingerprint.method

        // Init ends with updateInfo(".info/connected", false) followed by return-void; flip it to true
        // so the app's connection watcher reports Firebase as connected. Patched first: higher index.
        val connectedKeyIndex = initMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.let {
                it.definingClass == "Lvs1;" && it.name == "d"
            } == true
        }
        val updateInfoIndex = initMethod.indexOfFirstInstructionOrThrow(connectedKeyIndex, Opcode.INVOKE_VIRTUAL)
        val updateInfo = initMethod.getInstruction<FiveRegisterInstruction>(updateInfoIndex)
        val (repo, key, value) = Triple(updateInfo.registerC, updateInfo.registerD, updateInfo.registerE)
        initMethod.addInstructions(updateInfoIndex + 1, """
            sget-object v$value, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            invoke-virtual {v$repo, v$key, v$value}, Lwj6;->p(Lm61;Ljava/lang/Object;)V
        """.trimIndent())

        // Seed right after the server sync tree (Repo.n) is assigned.
        val serverSyncTreeIndex = initMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.IPUT_OBJECT && getReference<FieldReference>()?.let {
                it.definingClass == "Lwj6;" && it.name == "n"
            } == true
        }
        val repoRegister = initMethod.getInstruction<TwoRegisterInstruction>(serverSyncTreeIndex).registerB
        initMethod.addInstructions(
            serverSyncTreeIndex + 1,
            "invoke-static {v$repoRegister}, Lwz5;->$INIT_METHOD(Lwj6;)V"
        )

        println("SwiftBackup: Firebase database client redirected to local store (connection interrupted, writes kept on device)")
    }
}

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

// Looked up by name from LocalDb; keep in sync with the extension.
private const val SEED_METHOD = "morpheLocalDbSeed"
private const val DOWNLOAD_METHOD = "morpheCloudDownload"
private const val UPLOAD_METHOD = "morpheCloudUpload"
private const val CLOUD_FILE_NAME = "morphe_local_db.json"

/**
 * Cloud-backup metadata lives in the vendor's Firebase Realtime Database, whose rules reject
 * accounts without a server-verified purchase ("Permission denied" on every read/write under users/<uid>).
 * This keeps the database client permanently offline and backs it with a JSON file instead:
 * the tree is seeded as server data on Repo init and every outgoing write is stored locally,
 * echoed back as a server overwrite and acked. The extension also syncs the file to the root folder
 * of the connected cloud (SMB, WebDAV, ...), so other devices and reinstalls see the same backups.
 */
@Suppress("unused")
val swiftBackupLocalCloudMetadataPatch = bytecodePatch(
    name = "Swift Backup Local Cloud Metadata",
    description = "Makes cloud backups work by keeping their metadata on the device and the connected cloud instead of the Swift Backup server.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)

    extendWith("extensions/swiftbackup.mpe")

    execute {
        // q86.e: replace the network put/merge. 3 locals + p0..p5; p1 is reused once its value is consumed.
        PersistentConnectionPutFingerprint.method.addInstructionsWithLabels(0, """
            invoke-static {p1, p2, p3}, $LOCAL_DB->write(Ljava/lang/String;Ljava/util/List;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            iget-object v1, p0, Lq86;->a:Lmt6;
            new-instance v2, Lz66;
            invoke-direct {v2, p2}, Lz66;-><init>(Ljava/util/List;)V
            sget-object p1, Li13;->e:Li13;
            invoke-static {v0, p1}, Lmn8;->a(Ljava/lang/Object;Lcw5;)Lcw5;
            move-result-object v0
            iget-object p1, v1, Lmt6;->n:Lf68;
            invoke-virtual {p1, v2, v0}, Lf68;->g(Lz66;Lcw5;)Ljava/util/List;
            move-result-object v0
            invoke-virtual {v1, v0}, Lmt6;->j(Ljava/util/List;)V
            if-eqz p5, :no_callback
            invoke-static {p5}, $LOCAL_DB->ack(Ljava/lang/Object;)Ljava/lang/Runnable;
            move-result-object v0
            invoke-virtual {v1, v0}, Lmt6;->n(Ljava/lang/Runnable;)V
            :no_callback
            return-void
        """.trimIndent())

        // Static helpers added to wz5. The extension only sees them reflectively, so every
        // obfuscated app/SDK type stays in this file.
        val connectionClass = PersistentConnectionPutFingerprint.classDef
        val helper = connectionClass.type

        fun addStaticHelper(
            name: String,
            parameters: List<String>,
            returnType: String,
            registerCount: Int,
            smali: String
        ) = connectionClass.methods.add(
            MutableMethod(
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
            ).apply { addInstructionsWithLabels(0, smali.trimIndent()) }
        )

        // Registers: parameters are spelled as absolute registers (p0 = registerCount - 1).
        // Interrupt the connection for good, pin a root listener so the server sync tree keeps
        // the whole tree, seed it, then hand the Repo to the extension for cloud syncing.
        addStaticHelper(INIT_METHOD, listOf("Lmt6;"), "V", 6, """
            iget-object v0, v5, Lmt6;->c:Lq86;
            const-string v1, "morphe-local-db"
            invoke-virtual {v0, v1}, Lq86;->c(Ljava/lang/String;)V
            sget-object v0, Lz66;->d:Lz66;
            new-instance v1, Lem6;
            sget-object v2, Lcm6;->f:Lcm6;
            invoke-direct {v1, v0, v2}, Lem6;-><init>(Lz66;Lcm6;)V
            const-class v2, Ldu8;
            invoke-static {v2}, $LOCAL_DB->noopListener(Ljava/lang/Class;)Ljava/lang/Object;
            move-result-object v2
            check-cast v2, Ldu8;
            new-instance v3, Leu8;
            invoke-direct {v3, v5, v2, v1}, Leu8;-><init>(Lmt6;Ldu8;Lem6;)V
            iget-object v4, v5, Lmt6;->n:Lf68;
            const/4 v1, 0x0
            invoke-virtual {v4, v3, v1}, Lf68;->d(Lu43;Z)Ljava/util/List;
            move-result-object v1
            invoke-virtual {v5, v1}, Lmt6;->j(Ljava/util/List;)V
            invoke-static {v5}, $helper->$SEED_METHOD(Lmt6;)V
            const-class v0, $helper
            const-string v1, "n"
            invoke-static {v5, v0, v1}, $LOCAL_DB->attach(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V
            return-void
        """)

        // Applies the local tree as a server overwrite at the root. Must run on the Repo run loop.
        addStaticHelper(SEED_METHOD, listOf("Lmt6;"), "V", 4, """
            invoke-static {}, $LOCAL_DB->load()Ljava/lang/Object;
            move-result-object v0
            sget-object v1, Li13;->e:Li13;
            invoke-static {v0, v1}, Lmn8;->a(Ljava/lang/Object;Lcw5;)Lcw5;
            move-result-object v0
            iget-object v1, v3, Lmt6;->n:Lf68;
            sget-object v2, Lz66;->d:Lz66;
            invoke-virtual {v1, v2, v0}, Lf68;->g(Lz66;Lcw5;)Ljava/util/List;
            move-result-object v0
            invoke-virtual {v3, v0}, Lmt6;->j(Ljava/util/List;)V
            return-void
        """)

        // Active cloud client (hj1) if a cloud is connected and it is a generic file-based one
        // (b92: SMB, WebDAV, ...); null otherwise.
        addStaticHelper("morpheCloudClient", listOf(), "Lhj1;", 2, """
            invoke-static {}, Ltd1;->m()Laf1;
            move-result-object v0
            if-eqz v0, :none
            invoke-static {}, Ltd1;->d()Lwd1;
            move-result-object v0
            instance-of v1, v0, Lb92;
            if-eqz v1, :none
            check-cast v0, Lb92;
            invoke-virtual {v0}, Lb92;->o()Lhj1;
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """)

        addStaticHelper("morpheCloudPath", listOf("Lhj1;"), "Ljava/lang/String;", 3, """
            invoke-interface {v2}, Lhj1;->f()Laf1;
            move-result-object v0
            invoke-virtual {v0}, Laf1;->getMainCloudFolderName()Ljava/lang/String;
            move-result-object v0
            const-string v1, "/$CLOUD_FILE_NAME"
            invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)

        // null: no usable client. Empty array: connected, but nothing stored yet.
        addStaticHelper(DOWNLOAD_METHOD, listOf(), "[B", 3, """
            invoke-static {}, $helper->morpheCloudClient()Lhj1;
            move-result-object v0
            if-nez v0, :client
            const/4 v0, 0x0
            return-object v0
            :client
            invoke-static {v0}, $helper->morpheCloudPath(Lhj1;)Ljava/lang/String;
            move-result-object v1
            invoke-interface {v0, v1}, Lhj1;->r(Ljava/lang/String;)Z
            move-result v2
            if-nez v2, :exists
            const/4 v0, 0x0
            new-array v0, v0, [B
            return-object v0
            :exists
            const/4 v2, 0x0
            invoke-interface {v0, v1, v2}, Lhj1;->c(Ljava/lang/String;Lt85;)Lmj4;
            move-result-object v0
            iget-object v1, v0, Lmj4;->a:Ljava/io/InputStream;
            invoke-static {v1}, Lg01;->D(Ljava/io/InputStream;)[B
            move-result-object v1
            invoke-virtual {v0}, Lmj4;->close()V
            return-object v1
        """)

        // Same upload the app uses for its own cloud id file (kh case 16 wraps a byte array).
        // v0..v5 are laid out for the range call: client, source, path, length (wide), null.
        addStaticHelper(UPLOAD_METHOD, listOf("[B"), "Z", 8, """
            invoke-static {}, $helper->morpheCloudClient()Lhj1;
            move-result-object v0
            if-nez v0, :client
            const/4 v0, 0x0
            return v0
            :client
            invoke-static {v0}, $helper->morpheCloudPath(Lhj1;)Ljava/lang/String;
            move-result-object v2
            new-instance v6, Lkh;
            const/16 v1, 0x10
            invoke-direct {v6, v7, v1}, Lkh;-><init>(Ljava/lang/Object;I)V
            new-instance v1, Lk92;
            invoke-direct {v1, v6}, Lk92;-><init>(Lcz3;)V
            array-length v3, v7
            int-to-long v3, v3
            const/4 v5, 0x0
            invoke-interface/range {v0 .. v5}, Lhj1;->j(Lk92;Ljava/lang/String;JLqz;)V
            const/4 v0, 0x1
            return v0
        """)

        val initMethod = RepoDeferredInitFingerprint.method

        // Init ends with updateInfo(".info/connected", false) followed by return-void; flip it to true
        // so the app's connection watcher reports Firebase as connected. Patched first: higher index.
        val connectedKeyIndex = initMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.let {
                it.definingClass == "Lvv1;" && it.name == "d"
            } == true
        }
        val updateInfoIndex = initMethod.indexOfFirstInstructionOrThrow(connectedKeyIndex, Opcode.INVOKE_VIRTUAL)
        val updateInfo = initMethod.getInstruction<FiveRegisterInstruction>(updateInfoIndex)
        val (repo, key, value) = Triple(updateInfo.registerC, updateInfo.registerD, updateInfo.registerE)
        initMethod.addInstructions(updateInfoIndex + 1, """
            sget-object v$value, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            invoke-virtual {v$repo, v$key, v$value}, Lmt6;->p(Lh81;Ljava/lang/Object;)V
        """.trimIndent())

        // Seed right after the server sync tree (Repo.n) is assigned.
        val serverSyncTreeIndex = initMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.IPUT_OBJECT && getReference<FieldReference>()?.let {
                it.definingClass == "Lmt6;" && it.name == "n"
            } == true
        }
        val repoRegister = initMethod.getInstruction<TwoRegisterInstruction>(serverSyncTreeIndex).registerB
        initMethod.addInstructions(
            serverSyncTreeIndex + 1,
            "invoke-static {v$repoRegister}, Lq86;->$INIT_METHOD(Lmt6;)V"
        )

        println("SwiftBackup: Firebase database client redirected to local store (connection interrupted, metadata synced to the connected cloud)")
    }
}

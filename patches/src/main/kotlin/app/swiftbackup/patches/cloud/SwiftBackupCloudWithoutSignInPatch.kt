package app.swiftbackup.patches.cloud

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction30t
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// jl4.a(Object, Object)Z — the null-safe equality the app uses to compare a Boolean with TRUE.
private const val EQUALITY = "Ljl4;"

/**
 * Lets the anonymous (local) account use the cloud. Swift Backup gates every cloud path behind
 * "is the account anonymous", because only a signed-in account may sync through its server. For the
 * file-based providers the client is built from locally stored credentials and never touches
 * Firebase, so the gates are pure boolean checks that can be dropped.
 *
 * Depends on the local metadata patch: the cloud backup catalog itself lives in the Realtime
 * Database, which is intercepted there, and an anonymous account can only read/write it once the
 * database client is redirected to the local store.
 */
@Suppress("unused")
val swiftBackupCloudWithoutSignInPatch = bytecodePatch(
    name = "Swift Backup Cloud Backup Without Sign-In",
    description = "Lets an anonymous account connect file-based cloud providers (SMB, WebDAV, SFTP, FTP, S3, Dropbox, OneDrive, Box, Mega, Filen, Drime, Yandex, pCloud, Mail.ru) and run cloud backups/restores without signing in with Google.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)
    dependsOn(swiftBackupLocalCloudMetadataPatch)

    execute {
        val connectGate = CloudConnectAnonGateFingerprint.method
        connectGate.forceBranch(connectGate.anonymousGateIndex())
        println("SwiftBackup: cloud connect no longer requires a Google sign-in")

        val clientGate = CloudClientGateFingerprint.method
        clientGate.forceFallthrough(clientGate.anonymousGateIndex())
        println("SwiftBackup: anonymous accounts get the real cloud client instead of the empty one")

        val currentClientGate = CurrentClientGateFingerprint.method
        currentClientGate.forceBranch(currentClientGate.anonymousGateIndex())
        println("SwiftBackup: anonymous accounts use the cached cloud client")

        val deleteTaggedGate = DeleteTaggedBackupsClientFingerprint.method
        deleteTaggedGate.forceBranch(deleteTaggedGate.anonymousGateIndex())
        println("SwiftBackup: deleteTaggedBackups reaches the cloud client for anonymous accounts")

        val uploadGate = UploadSessionStartFingerprint.method
        uploadGate.forceFallthrough(uploadGate.anonymousGateIndex())
        println("SwiftBackup: anonymous accounts can start cloud uploads")

        val downloadGate = DownloadSessionStartFingerprint.method
        downloadGate.forceFallthrough(downloadGate.anonymousGateIndex())
        println("SwiftBackup: anonymous accounts can start cloud downloads")

        val readGate = ReadReferenceGateFingerprint.method
        readGate.forceBranch(readGate.anonymousGateIndex())
        println("SwiftBackup: anonymous accounts can read cloud references")

        val setValueGate = SetValueGateFingerprint.method
        setValueGate.forceBranch(setValueGate.anonymousGateIndex())
        println("SwiftBackup: anonymous accounts can write cloud values")
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
 * The comparison is matched rather than the isAnonymous() call, because two of the gates do not
 * look like the others: af1.getClient() computes the flag inside a synthetic lambda (no
 * isAnonymous() in the method at all), and nl7.m() contains a second jl4.a comparison that is not
 * a gate. Every real gate has the exact shape `invoke-static jl4.a(..)` → `move-result` →
 * `if-eqz`/`if-nez`, which this search relies on.
 */
private fun MutableMethod.anonymousGateIndex(): Int {
    val instructionList = instructions
    for (index in 0 until instructionList.size - 2) {
        if (!instructionList[index].isAnonymousGate()) continue
        if (instructionList[index + 1].opcode != Opcode.MOVE_RESULT) continue
        val branch = instructionList[index + 2].opcode
        if (branch == Opcode.IF_EQZ || branch == Opcode.IF_NEZ) return index + 2
    }
    error("SwiftBackup: no anonymous gate found")
}

/**
 * Forces the branch to be taken by replacing it with an unconditional goto.
 *
 * The goto has to be built against the branch's own label: a `replaceInstruction(index, "goto :x")`
 * snippet cannot resolve a label that lives in the target method.
 */
private fun MutableMethod.forceBranch(index: Int) {
    val target = getInstruction<BuilderOffsetInstruction>(index).target
    replaceInstruction(index, BuilderInstruction30t(Opcode.GOTO_32, target))
}

/** Forces fall-through (the branch is never taken). */
private fun MutableMethod.forceFallthrough(index: Int) {
    replaceInstruction(index, BuilderInstruction10x(Opcode.NOP))
}

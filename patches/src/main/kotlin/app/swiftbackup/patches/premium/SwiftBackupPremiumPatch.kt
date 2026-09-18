package app.swiftbackup.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.returnEarly
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP
import com.android.tools.smali.dexlib2.Opcode

/**
 * Login-free premium: V.getA→true + d45.a never-null (offline anon seed) + c64.j ignore-false.
 * v1/v0 clobber safe; schedule early-return sits before monitor-enter (no deadlock).
 * See analysis/swiftbackup/notes/offline-premium.md.
 */
@Suppress("unused")
val swiftBackupPremiumPatch = bytecodePatch(
    name = "Swift Backup Premium Unlock",
    description = "Unlocks all Premium features.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)

    execute {
        PremiumGateFingerprint.method.returnEarly(true)

        val authMethod = AuthStateRootFingerprint.method
        // LAST return-object is the null path; mint resolved at patch time (no obfuscated names hardcoded).
        val nullReturnIndex =
            authMethod.indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
        val mint = OfflineAnonMintFingerprint.method
        val mintRef = "${mint.definingClass}->${mint.name}()${mint.returnType}"
        authMethod.addInstructions(nullReturnIndex, """
            invoke-static {}, $mintRef
            move-result-object v1
            sget-object v0, Lorg/swiftapps/swiftbackup/common/V;->INSTANCE:Lorg/swiftapps/swiftbackup/common/V;
            invoke-virtual {v0, v1}, Lorg/swiftapps/swiftbackup/common/V;->setNon(Lorg/swiftapps/swiftbackup/anonymous/MFirebaseUser;)V
        """.trimIndent())
        println("SwiftBackup: seeded offline anon in auth-state root via $mintRef")

        ScheduleEnableGuardFingerprint.method.addInstructionsWithLabels(0, """
            if-nez p1, :keep_enabled
            return-void
            :keep_enabled
            nop
        """.trimIndent())
        println("SwiftBackup: c64.j now ignores false (schedule engine hardening)")
    }
}

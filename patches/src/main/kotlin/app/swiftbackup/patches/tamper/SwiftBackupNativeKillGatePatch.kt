package app.swiftbackup.patches.tamper

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP

/**
 * Skips System.loadLibrary("native-lib") in onCreate and inits Firebase from google-services resources at the same point.
 * JNI_OnLoad runs tamper checks (SIGABRT on resigned) and is the only FirebaseApp initializer — both halves required.
 * v0 clobber safe; NOP in place. Must run alongside Premium Unlock. See analysis/swiftbackup/notes/native-kill-switch.md.
 */
@Suppress("unused")
val swiftBackupNativeKillGatePatch = bytecodePatch(
    name = "Swift Backup Tamper Protection",
    description = "Stops the app from closing itself on patched installs.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)

    execute {
        val matches = SwiftAppOnCreateNativeLoadFingerprint.instructionMatches
        if (matches.size < 2) {
            throw PatchException(
                "Native kill-gate: expected 2 matches (const-string + loadLibrary), " +
                    "found ${matches.size} - app layout changed?"
            )
        }
        val method = SwiftAppOnCreateNativeLoadFingerprint.method
        // Insert Firebase init BEFORE the load site (same onCreate position where
        // JNI_OnLoad used to initialize Firebase, ahead of the gg3 block).
        // Insert first so the match indices below stay valid (+2 shift).
        method.addInstructions(matches[0].index, """
            invoke-static {p0}, Lcom/google/firebase/FirebaseApp;->initializeApp(Landroid/content/Context;)Lcom/google/firebase/FirebaseApp;
            move-result-object v0
        """.trimIndent())
        // NOP both load instructions in place (indices stay valid: replace, not remove).
        // matches[0] = const-string v0, "native-lib"; matches[1] = System.loadLibrary invoke.
        method.replaceInstruction(matches[0].index + 2, "nop")
        method.replaceInstruction(matches[1].index + 2, "nop")
        println("SwiftBackup: NOPped loadLibrary(\"native-lib\") + inserted FirebaseApp.initializeApp in SwiftApp.onCreate")
    }
}

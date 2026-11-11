package app.swiftbackup.patches.backup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Anonymous settings export (g48.invokeSuspend): serialises
 * SwiftBackupSettingsHelper$SettingsData with Gson and writes it to the picked Uri.
 *
 * Pinned by the log line, the Gson toJson call and the file write helper.
 * The two class names are R8-renamed and pinned to 5.1.1 by COMPATIBILITY_SWIFTBACKUP.
 * See analysis/swiftbackup/notes/local-account-backup-plan.md §5.4.
 */
object SettingsExportFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        string("Exporting settings to "),
        methodCall(definingClass = "Lcom/google/gson/a;", name = "i"),
        methodCall(definingClass = "Lgf4;", name = "e")
    )
)

/**
 * Anonymous settings import (h48.invokeSuspend): reads the picked Uri into a String, parses it with
 * Gson into SwiftBackupSettingsHelper$SettingsData and calls restore() (which restarts the app).
 *
 * Pinned by the log line and the SettingsData.restore() call — no obfuscated names needed.
 * See analysis/swiftbackup/notes/local-account-backup-plan.md §5.4.
 */
object SettingsImportFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        string("Importing settings from "),
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/settings/SwiftBackupSettingsHelper\$SettingsData;",
            name = "restore"
        )
    )
)

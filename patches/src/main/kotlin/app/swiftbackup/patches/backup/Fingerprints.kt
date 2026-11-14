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

/**
 * nl7.m() (SettingsActivity.onCreatePreferences): the export_settings/import_settings
 * Preference.D(true) calls sit behind an isAnonymous check, so the anonymous account never sees
 * them. Pinned to 5.1.1 (class name is R8-renamed).
 *
 * Gate: smali2/nl7.smali:3147 (`if-eqz v0, :cond_1a1`). m() contains more than one jl4.a(Object,
 * Object) comparison, so the patch does not search textually: the gate is the comparison whose
 * result is consumed straight away by move-result + if-eqz (the isAnonymous flag), and that one is
 * followed by the Preference.D(true) block.
 */
object SettingsExportImportVisibilityFingerprint : Fingerprint(
    definingClass = "Lnl7;",
    name = "m",
    parameters = listOf(),
    returnType = "V"
)

/**
 * nl7.e(Preference) (SettingsActivity.onPreferenceTreeClick): the export_settings and
 * import_settings branches each run two isAnonymous() checks before launching the SAF document
 * picker, so a non-anonymous (Google) account returns without doing anything.
 *
 * All four gates are `jl4.a(isAnonymous, TRUE)` → `move-result` → `if-nez`:
 * smali2/nl7.smali:837 + :900 (export) and :1338 + :1401 (import). These are the only gates of
 * that shape in the method. Pinned to 5.1.1 (class name is R8-renamed).
 */
object SettingsExportImportClickFingerprint : Fingerprint(
    definingClass = "Lnl7;",
    name = "e",
    parameters = listOf("Landroidx/preference/Preference;"),
    returnType = "Z"
)

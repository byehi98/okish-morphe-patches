package app.moviebox.patches.update

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.transsion.version.update.RemoteVersionInfo.getForceUpdate()Z
 *
 * Parcelable carrying the server update response. When forceUpdate is true
 * the app blocks usage until the update is installed. Stable Kotlin getter
 * in a non-obfuscated class.
 */
object ForceUpdateFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/version/update/RemoteVersionInfo;",
    name = "getForceUpdate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

/**
 * com.transsion.version.update.RemoteVersionInfo.getHasUpdate()Z
 *
 * When true the UpdateDialog is shown. Forcing false suppresses the update
 * prompt entirely (covers both Tinker patch updates and full-APK installs).
 */
object HasUpdateFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/version/update/RemoteVersionInfo;",
    name = "getHasUpdate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

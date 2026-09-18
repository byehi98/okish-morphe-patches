package app.swiftbackup.patches.tamper

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * SwiftApp.onCreate() native kill-switch gate (System.loadLibrary("native-lib")).
 * Pinned by definingClass + string/call order — no obfuscated names; string unique app-wide.
 * See analysis/swiftbackup/notes/native-kill-switch.md.
 */
object SwiftAppOnCreateNativeLoadFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/SwiftApp;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("native-lib"),
        methodCall(
            definingClass = "Ljava/lang/System;",
            name = "loadLibrary"
        )
    )
)

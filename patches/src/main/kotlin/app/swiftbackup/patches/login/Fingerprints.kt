package app.swiftbackup.patches.login

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * SwiftApp.getGoogleAuthWebClientId(): GMS sign-in server client ID (UnsatisfiedLinkError crash site).
 * Pinned by definingClass + JNI-pinned name only — no body filters on natives, no accessFlags (NATIVE exact-match).
 * See analysis/swiftbackup/notes/google-auth-stub.md.
 */
object GoogleAuthWebClientIdFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/SwiftApp;",
    name = "getGoogleAuthWebClientId",
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

/**
 * SwiftApp.getGoogleAuthAndroidClientId(): No-GMS OAuth client ID.
 * JNI-pinned name; do-NOT-swap with the web ID. See analysis/swiftbackup/notes/google-auth-stub.md.
 */
object GoogleAuthAndroidClientIdFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/SwiftApp;",
    name = "getGoogleAuthAndroidClientId",
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

/**
 * SwiftApp.isLocalNetworkTcpBlockedByPermission(): Wi-Fi-transfer TCP probe.
 * JNI-pinned name; stub must be false (true fabricates a blocked-network diagnosis).
 * See analysis/swiftbackup/notes/google-auth-stub.md.
 */
object LocalNetworkTcpProbeFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/SwiftApp;",
    name = "isLocalNetworkTcpBlockedByPermission",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "I", "I")
)

/**
 * V.getG(): use-browser-auth flag (SharedPreferences read).
 * Pinned by definingClass + pref-key string + getBoolean order — no obfuscated names.
 * See analysis/swiftbackup/notes/web-login-port.md.
 */
object BrowserAuthFlagFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/common/V;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("8475d34h4k"),
        methodCall(
            definingClass = "Landroid/content/SharedPreferences;",
            name = "getBoolean"
        )
    )
)

/**
 * Replaces a `native` String getter with a PUBLIC|FINAL const-string stub (NATIVE-stripped MutableMethod pattern).
 */
internal fun convertNativeGetterToStringStub(
    mutableClass: MutableClass,
    methodName: String,
    value: String
) {
    val original = mutableClass.methods.firstOrNull { it.name == methodName }
        ?: throw PatchException(
            "Google-auth stub: method $methodName not found in ${mutableClass.type} - app layout changed?"
        )

    // Instance method, no params: p0 (this) sits at the top; v0 is a free local.
    val stub = MutableMethod(
        ImmutableMethod(
            mutableClass.type,
            methodName,
            original.parameters,
            "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(4)
        )
    ).apply {
        addInstructions(0, """
            const-string v0, "$value"
            return-object v0
        """)
    }

    mutableClass.methods.remove(original)
    mutableClass.methods.add(stub)
}

/**
 * Replaces the native TCP probe with a PUBLIC|FINAL false stub (NATIVE-stripped pattern; v0 clobber safe).
 */
internal fun convertNativeProbeToFalseStub(
    mutableClass: MutableClass,
    methodName: String
) {
    val original = mutableClass.methods.firstOrNull { it.name == methodName }
        ?: throw PatchException(
            "Google-auth stub: method $methodName not found in ${mutableClass.type} - app layout changed?"
        )

    // Instance method with (String,I,I) params: 4 param words, so v0/v1 are
    // free locals with 6 total registers.
    val stub = MutableMethod(
        ImmutableMethod(
            mutableClass.type,
            methodName,
            original.parameters,
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(6)
        )
    ).apply {
        addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """)
    }

    mutableClass.methods.remove(original)
    mutableClass.methods.add(stub)
}

package app.swiftbackup.patches.login

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP

/**
 * Web login: V.getG→true (AppAuth browser flow; GMS One-Tap is cert-bound) + 3 SwiftApp native stubs.
 * Kill-gate NOPs loadLibrary so the JNI exports are unreachable without stubs; IDs must not be swapped.
 * See analysis/swiftbackup/notes/web-login-port.md + google-auth-stub.md.
 */
@Suppress("unused")
val swiftBackupWebLoginPatch = bytecodePatch(
    name = "Swift Backup Web Login",
    description = "Makes Google sign-in work for connecting cloud accounts.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)

    execute {
        BrowserAuthFlagFingerprint.method.returnEarly(true)

        // Force-resolve all three fingerprints first (throws on no/excess match).
        GoogleAuthWebClientIdFingerprint.method
        GoogleAuthAndroidClientIdFingerprint.method
        LocalNetworkTcpProbeFingerprint.method
        val swiftAppClass = GoogleAuthWebClientIdFingerprint.classDef

        convertNativeGetterToStringStub(
            swiftAppClass,
            "getGoogleAuthWebClientId",
            "65312358122-idbambk57rq6g86evl46jv5hikve21pp.apps.googleusercontent.com"
        )
        convertNativeGetterToStringStub(
            swiftAppClass,
            "getGoogleAuthAndroidClientId",
            "65312358122-rain54ntj087k3lqousgb4l94isnvrqb.apps.googleusercontent.com"
        )
        convertNativeProbeToFalseStub(
            swiftAppClass,
            "isLocalNetworkTcpBlockedByPermission"
        )
        println("SwiftBackup: stubbed 3 SwiftApp natives (2 OAuth client IDs + TCP probe->false)")
    }
}

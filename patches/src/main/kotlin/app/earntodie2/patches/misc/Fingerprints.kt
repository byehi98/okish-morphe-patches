package app.earntodie2.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * AndroidUtils.getAppPublicKey()Ljava/lang/String; — called by the native
 * libEarnToDie2.so (FGKit::AndroidUtils::GetAppPublicKey via JNI) at startup.
 * Returns the base64 (NO_WRAP) SPKI public key of the current signing cert.
 * The C++ side compares it against the original Not Doppler Play Store key
 * and shows "Invalid signature" on mismatch.
 *
 * Confirmed smali (classes3/org/cocos2dx/cpp/AndroidUtils.smali:124):
 *   .method public static getAppPublicKey()Ljava/lang/String;
 *   .registers 3
 *   ...getPackageInfo(pkg, 0x40).signatures[0] → X.509 → getPublicKey().getEncoded()
 *   → Base64.encodeToString(, 2)
 *
 * Stable filters: the X.509 CertificateFactory string and getEncoded() call.
 */
object GetAppPublicKeyFingerprint : Fingerprint(
    definingClass = "Lorg/cocos2dx/cpp/AndroidUtils;",
    name = "getAppPublicKey",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        literal(0x40),  // flags for getPackageInfo(pkg, 0x40)
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getPackageInfo"
        ),
        methodCall(
            definingClass = "Ljava/security/cert/CertificateFactory;",
            name = "generateCertificate"
        )
    )
)

/**
 * AndroidUtils.getInstallerPackage()Ljava/lang/String; — called by the native
 * libEarnToDie2.so (via JNI) at startup. The C++ side compares the returned
 * installer package against the hardcoded "com.android.vending" and, on
 * mismatch, shows the dialog "Please download the game from Google Play".
 *
 * On sideloaded installs getInstallerPackageName() returns null → the native
 * check fails and the popup appears. We hardcode the Play Store package so
 * the native check always passes.
 *
 * Confirmed smali (classes3/org/cocos2dx/cpp/AndroidUtils.smali:313):
 *   .method public static getInstallerPackage()Ljava/lang/String;
 *   .registers 2
 *   ...getPackageManager() → ...getPackageName() → getInstallerPackageName()
 */
object GetInstallerPackageFingerprint : Fingerprint(
    definingClass = "Lorg/cocos2dx/cpp/AndroidUtils;",
    name = "getInstallerPackage",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/Context;",
            name = "getPackageName"
        ),
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getInstallerPackageName"
        )
    )
)

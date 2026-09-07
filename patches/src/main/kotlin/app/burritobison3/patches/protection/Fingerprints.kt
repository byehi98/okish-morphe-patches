package app.burritobison3.patches.protection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Kongregate APK signature-hash self-check (smali-verified in
 * classes3/com/kongregate/o/m/j.smali):
 *
 *   .method public static a(Landroid/content/Context;I)Z
 *     getPackageManager().getPackageInfo(pkg, 0x40).signatures →
 *     Signature.hashCode() compared against the expected int.
 *
 * Required enabler: a re-signed/patched APK fails this check otherwise.
 * Fingerprint anchors on the PackageManager → PackageInfo.signatures →
 * Signature.hashCode() chain (in smali order). The defining class (o/m/j)
 * is obfuscated and deliberately NOT referenced.
 */
object SigCheckFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "I"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/Context;",
            name = "getPackageManager"
        ),
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getPackageInfo"
        ),
        fieldAccess(smali = "Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;"),
        methodCall(
            definingClass = "Landroid/content/pm/Signature;",
            name = "hashCode"
        )
    )
)

/**
 * Kongregate root check (smali-verified in
 * classes3/com/kongregate/o/m/c.smali):
 *
 *   .method public static k()Z
 *     Build.TAGS contains "test-keys" OR /system/app/Superuser.apk exists.
 *
 * Fingerprint anchors on both const-strings in smali order. Trivial,
 * low-risk; always applied alongside the re-sign enabler above.
 */
object RootCheckFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("test-keys"),
        string("/system/app/Superuser.apk")
    )
)

/**
 * Kongregate device-type classifier (smali-verified private static a() in
 * classes3/com/kongregate/o/m/c.smali). Reads Build.PRODUCT / DEVICE / MODEL
 * plus the "ro.kernel.qemu" system property and maps the device to an enum:
 * "sdk" / "google_sdk" / qemu / generic → EMULATOR (constant `c`, which the
 * public j() emulator check compares against), anything unrecognized →
 * default physical-device constant `e`.
 *
 * returnType is intentionally omitted (the enum type is obfuscated);
 * access flags + empty params + the ordered stable filters
 * (Build.PRODUCT field → "ro.kernel.qemu" → "google_sdk") uniquely identify
 * it. The patch forces the default physical-device return so j() never
 * reports EMULATOR.
 */
object DeviceClassifierFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(smali = "Landroid/os/Build;->PRODUCT:Ljava/lang/String;"),
        string("ro.kernel.qemu"),
        string("google_sdk")
    )
)

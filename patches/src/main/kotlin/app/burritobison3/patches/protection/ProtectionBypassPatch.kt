package app.burritobison3.patches.protection

import app.burritobison3.patches.shared.Constants.COMPATIBILITY_BURRITO_BISON
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly

/**
 * Required enablers for the free-purchases track on a re-signed APK — without
 * these, the Kongregate SDK rejects the patched package / environment before
 * any entitlement code runs:
 *
 *  - Signature-hash self-check → always passes (re-sign safe).
 *  - Root check (test-keys / Superuser.apk) → never rooted.
 *  - Device classifier → default physical-device constant, so the
 *    emulator check (d() == EMULATOR) never fires.
 *
 * NOTE on the classifier return: the enum constants are obfuscated
 * (c$a;->a..e); `e` is the method's own fallthrough return for
 * unrecognized physical devices (line 30 of the smali), and `c` is the
 * EMULATOR value the j() check compares against — verified by reading the
 * method body, not by name. This single obfuscated sget is unavoidable and
 * documented here; the fingerprint itself anchors only on stable strings.
 */
@Suppress("unused")
val burritoBisonProtectionBypassPatch = bytecodePatch(
    name = "Fix app opening",
    description = "Required so the modified app opens without crashing — skips signature, root and emulator checks."
) {
    compatibleWith(COMPATIBILITY_BURRITO_BISON)

    execute {
        // Re-signed APK passes the signature-hash check.
        SigCheckFingerprint.method.returnEarly(true)
        // Device never reports rooted.
        RootCheckFingerprint.method.returnEarly(false)
        // Classifier returns the default physical-device constant (v0 is
        // free at method entry; we return before any original instruction).
        DeviceClassifierFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Lcom/kongregate/o/m/c${'$'}a;->e:Lcom/kongregate/o/m/c${'$'}a;
                return-object v0
            """
        )
    }
}

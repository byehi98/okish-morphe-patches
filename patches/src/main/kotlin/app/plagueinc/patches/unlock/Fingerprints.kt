package app.plagueinc.patches.unlock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * Unlocks.refresh() — used only as an anchor to obtain the mutable
 * Lcom/miniclip/plagueinc/jni/Unlocks; class. All methods in this class are
 * `public static native` declarations backed by libPlagueIncAndroidNative.so.
 *
 * Confirmed smali (classes3/com/miniclip/plagueinc/jni/Unlocks.smali):
 *   .class public final Lcom/miniclip/plagueinc/jni/Unlocks;
 *   .method public static native refresh()V  (line 64)
 */
object UnlocksClassFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/jni/Unlocks;",
    name = "refresh",
    returnType = "V"
)

/**
 * Converts a `public static native` boolean method of the Unlocks class into
 * a regular Java method that always returns true.
 *
 * This is cleaner than patching every call site: every
 * `invoke-static {..}, Lcom/miniclip/plagueinc/jni/Unlocks;-><name>(...)Z`
 * across all menus automatically receives true once the native qualifier is
 * removed and a body is supplied.
 */
internal fun convertNativeUnlockMethodToTrueStub(mutableClass: MutableClass, methodName: String) {
    val original = mutableClass.methods.first { it.name == methodName }

    // Native methods have no code item, so the original must be replaced by
    // a fresh method carrying PUBLIC|STATIC (no NATIVE) plus a true-stub body.
    val stub = MutableMethod(
        ImmutableMethod(
            mutableClass.type,
            methodName,
            original.parameters,
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(4)
        )
    ).apply {
        addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)
    }

    mutableClass.methods.remove(original)
    mutableClass.methods.add(stub)
}

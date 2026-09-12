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

/**
 * Genes.getLockedGeneCount() — native gene-progression counter.
 *
 * Confirmed smali (classes5/com/miniclip/plagueinc/jni/Genes.smali line 52):
 *   .method public static native getLockedGeneCount()I
 *
 * Used as an anchor proving the Genes JNI bridge exists, and as the loop
 * condition source for the gene unlock-all injection (Option B).
 */
object GenesLockedCountFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/jni/Genes;",
    name = "getLockedGeneCount",
    returnType = "I"
)

/**
 * Unlocks.unlockRandomGene() — native "earn one gene" entry point.
 *
 * Confirmed smali (classes3/com/miniclip/plagueinc/jni/Unlocks.smali line 70):
 *   .method public static native unlockRandomGene()V
 *
 * This is the same native the game calls on a real `fullversion` purchase
 * (PurchaseManager.handlePurchase) and on legitimate gene earn. The unlock-all
 * loop replays it until the locked pool is drained.
 */
object UnlocksUnlockRandomGeneFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/jni/Unlocks;",
    name = "unlockRandomGene",
    returnType = "V"
)

/**
 * GenesMenu.showMainPage(Z) — gene menu entry, Option B injection target.
 *
 * Confirmed smali (classes6/com/miniclip/plagueinc/menu/GenesMenu.smali line 2421):
 *   .method private showMainPage(Z)V (.registers 7)
 *
 * Menu entry is safe: the engine is already init here (the method itself calls
 * Genes.firstActiveGeneInCategory / getDisplayUnlockedGeneCount further down),
 * and every onShow path funnels through here. Injection goes at method entry
 * (index 0), never between an invoke-* and its move-result.
 */
object GenesMenuShowMainPageFingerprint : Fingerprint(
    definingClass = "Lcom/miniclip/plagueinc/menu/GenesMenu;",
    name = "showMainPage",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Z")
)

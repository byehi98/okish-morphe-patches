package app.plagueinc.patches.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsToEnd
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * The Java-side unlock stubs (see the unlock/ patches) only affect Java
 * callers — the native engine keeps its own purchase registry and runs
 * un-owned premium plague modes in "trial" mode with a premium banner.
 *
 * This patch registers full entitlements directly with the engine, replaying
 * exactly what notifyPurchase does after a real purchase:
 *
 *   Main.setIsPremium(true)
 *   Unlocks.setInAppPurchaseState(<sku>, sig="", json="", purchased=true)  per SKU
 *
 * SKU set (internal ids observed in smali):
 *   - "fullversion"          global premium product
 *   - "necroa"               Necroa Virus ("zombie" is remapped to this)
 *   - "cure_expansion"       Cure Mode
 *   - "cure_expansion_premium" alt Cure Mode id (getCureExpansionID)
 *   - "zombie" / "neurax" / "simian_flu" / "vampire" / "xenolith"
 *                            disease identifiers passed to Unlocks.isAvailable
 *
 * Implementation note: <init> has only two free registers (.registers 4), not
 * enough for the 4-arg native call, so the logic lives in a synthetic static
 * method added to PurchaseManager and invoked once from the constructor.
 */
@Suppress("unused")
val plagueIncEngineEntitlementSyncPatch = bytecodePatch(
    name = "Plague Inc. Sync Premium Entitlements",
    description = "Registers unlocked premium content with the native engine so premium modes run without trial banners.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        val pmClass = PurchaseManagerInitFingerprint.classDef // mutable class

        // Synthetic static helper: .registers 5 gives v0..v4 freely.
        val syncMethod = MutableMethod(
            ImmutableMethod(
                pmClass.type,
                "morpheSyncEntitlements",
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(5)
            )
        ).apply {
            val skus = listOf(
                "fullversion",
                "necroa",
                "cure_expansion",
                "cure_expansion_premium",
                "zombie",
                "neurax",
                "simian_flu",
                "vampire",
                "xenolith"
            )

            addInstructions(
                0,
                """
                const/4 v4, 0x1
                invoke-static {v4}, Lcom/miniclip/plagueinc/jni/Main;->setIsPremium(Z)V
                """.trimIndent()
            )

            skus.forEach { sku ->
                addInstructions(
                    2,
                    """
                    const-string v0, "$sku"
                    const-string v1, ""
                    invoke-static {v0, v1, v1, v4}, Lcom/miniclip/plagueinc/jni/Unlocks;->setInAppPurchaseState(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V
                    """.trimIndent()
                )
            }

            addInstructionsToEnd("return-void")
        }

        pmClass.methods.add(syncMethod)

        // Call it first thing in the constructor. Zero register cost.
        PurchaseManagerInitFingerprint.method.addInstructions(
            0,
            "invoke-static {}, ${pmClass.type}->morpheSyncEntitlements()V"
        )
    }
}

package app.smashit.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * AndroidStore.ownsPremiumProduct() — top-level Java-side premium check.
 * Confirmed smali: public ownsPremiumProduct()Z
 */
object OwnsPremiumProductFingerprint : Fingerprint(
    definingClass = "Lcom/mediocre/smashhit/AndroidStore;",
    name = "ownsPremiumProduct",
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC)
)

/**
 * CommandThreadsafeModel.isProductOwned(String) — per-product check.
 * Called by the "isproductowned" JNI command handler.
 * Confirmed smali: public declared-synchronized isProductOwned(String)Z
 */
object IsProductOwnedFingerprint : Fingerprint(
    definingClass = "Lcom/mediocre/smashhit/CommandThreadsafeModel;",
    name = "isProductOwned",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/HashSet;",
            name = "contains"
        )
    )
)

/**
 * "hasrefreshedownedproducts" command handler lambda in CommandHandler.
 * The C++ engine polls this BEFORE it ever calls "isproductowned".
 * Must return "true" string for the engine to proceed to the product check.
 * Confirmed smali: synthetic lambda$setupCommands$1$...(String[])String
 */
object HasRefreshedOwnedProductsFingerprint : Fingerprint(
    definingClass = "Lcom/mediocre/smashhit/CommandHandler;",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.SYNTHETIC),
    parameters = listOf("[Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/atomic/AtomicBoolean;",
            name = "get"
        ),
        methodCall(
            definingClass = "Lcom/mediocre/smashhit/CommandHandler;",
            name = "boolToString"
        )
    )
)

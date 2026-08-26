package app.onlyone.patches.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.onlyone.patches.shared.Constants.COMPATIBILITY_ONLY_ONE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Full in-app catalog from resource.car → IAPGoogle.lu (hunt-findings.md §2).
// The Lua listener matches transaction.productIdentifier against store.products
// loaded from the REAL Play catalog; SKUs absent from the live catalog fail that
// match silently, so fabricating all of them is harmless.
private val FREE_PRODUCT_IDS = listOf(
    // Critical: grants Ultimate Power + sets isBuyer → also removes ads.
    "com.rebelbinary.onlyone.ultimatepower",
    // Character unlocks.
    "com.rebelbinary.onlyone.class.barbarian",
    "com.rebelbinary.onlyone.class.dwarf",
    "com.rebelbinary.onlyone.class.ninja",
    "com.rebelbinary.onlyone.class.onlyone",
    "com.rebelbinary.onlyone.class.valkyrie",
    // Bundles (character + UP / XP + currency).
    "com.rebelbinary.onlyone.bundle1",
    "com.rebelbinary.onlyone.bundle2",
    "com.rebelbinary.onlyone.bundle3",
    // Consumable XP powers.
    "com.rebelbinary.onlyone.power1",
    "com.rebelbinary.onlyone.power2",
    "com.rebelbinary.onlyone.power4",
    "com.rebelbinary.onlyone.power9",
    "com.rebelbinary.onlyone.power10"
)

// validateReceipt (IAPGoogle.lu main.0.4) only checks string.len(signature) > 10;
// content is irrelevant because Security.verifyPurchase is opened by step 1.
private const val FORGED_SIGNATURE = "MORPHESIGNATUREMORPHESIGNATURE"

/**
 * Builds a Purchase-parsable JSON row for one product. Field requirements:
 * - "productId" present and no "productIds" key → Billing 8's parser (Purchase.zza,
 *   classes/com/android/billingclient/api/Purchase.smali:48) returns exactly [productId],
 *   which StoreTransactionRuntimeTask pushes as transaction.productIdentifier.
 * - "purchaseState":0 → getPurchaseState()==1 (only raw value 4 maps to 2) → task
 *   state "purchased" (StoreTransactionRuntimeTask.smali:52-111).
 * - orderId/purchaseToken non-null, purchaseTime non-zero → validateReceipt passes.
 * - receipt == originalJson contains productId → Lua substring heuristic passes.
 */
private fun forgedPurchaseJson(productId: String): String =
    "{\"orderId\":\"morpherelease.fake.${productId.substringAfterLast('.')}\"," +
        "\"productId\":\"$productId\"," +
        "\"purchaseState\":0," +
        "\"purchaseTime\":1700000000000," +
        "\"purchaseToken\":\"morpherelease.token.$productId\"," +
        "\"packageName\":\"com.rebelbinary.onlyone\"," +
        "\"quantity\":1}"

/**
 * Escapes a value for embedding inside a smali `const-string` literal. The JSON
 * payload is full of double quotes; without this the smali assembler would end
 * the string at the first inner quote and fail at patch-execution time.
 */
private fun smaliEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Smali block constructing one fabricated Purchase and adding it to v1 (the
 * shared restore result list). Register budget inside restore()
 * (.registers 8; locals v0-v5): v0 holds the method's return value and is left
 * untouched; v2/v3/v4 are scratch registers that the original bytecode always
 * overwrites before its next read (next uses: new-instance $5 → iget fBillingClient
 * → iget SUBS), so clobbering them here is safe.
 */
private fun forgedPurchaseBlock(productId: String): String = """
    new-instance v2, Lcom/android/billingclient/api/Purchase;
    const-string v3, "${smaliEscape(forgedPurchaseJson(productId))}"
    const-string v4, "$FORGED_SIGNATURE"
    invoke-direct {v2, v3, v4}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
""".trimIndent()

/**
 * Only One — Free Store.
 *
 * Makes the game's own "Restore Purchases" button grant every catalog product
 * for free by fabricating `com.android.billingclient.api.Purchase` rows inside
 * the restore result pipeline:
 *
 * 1. Open the Java signature gate. Contrary to free-store-feasibility.md §4#1,
 *    this build's restore listeners (LuaLoader$6/$7.onQueryPurchasesResponse)
 *    funnel their merged purchase list through onPurchasesUpdated, which runs
 *    Security.verifyPurchase on EVERY row (verified: LuaLoader$7.smali:173,
 *    LuaLoader.smali:2294). A forged row cannot pass real SHA1withRSA crypto,
 *    and the empty-signature fallback only accepts "android.test.purchased"
 *    (which then fails validateReceipt's len(signature) > 10). So verifyPurchase
 *    is pinned to return true first — fabricated AND legitimately-owned rows now
 *    dispatch as purchased events.
 *
 * 2. Inject one fabricated Purchase per catalog SKU into the shared ArrayList
 *    that restore() hands to both queryPurchasesAsync listeners. Each tap of
 *    Restore Purchases then dispatches storeTransaction(state="purchased")
 *    events for all products through the stock pipeline: CoronaRuntimeTaskDispatcher
 *    threading, Lua event table, IAPGoogle.lu validateReceipt (receipt contains
 *    productId; signature length > 10), product matching, grant, save().
 *
 * Granting Ultimate Power sets player.isBuyer, which uiAds.lu uses to compute
 * fullAds=false — so this patch also disables all AdMob ads without touching
 * the admob plugin. Entitlements persist via the game's own save system; there
 * is no server-side re-validation anywhere in the chain (feasibility §5).
 *
 * Precondition inherited from the engine: the billing listener must be
 * registered (fListener != -1), which happens as soon as the in-game store UI
 * is opened — the Restore button itself lives inside that UI, so tapping it
 * satisfies the precondition by construction.
 */
@Suppress("unused")
val onlyOneFreeStorePatch = bytecodePatch(
    name = "Only One Free Store",
    description = "Makes 'Restore Purchases' grant every store item for free " +
        "(Ultimate Power, all classes, bundles and powers) — also removes ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ONLY_ONE)

    execute {
        // Step 1 — open the signature gate so any Purchase row dispatches.
        SecurityVerifyPurchaseFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """.trimIndent())

        // Step 2 — fabricate purchases into restore()'s shared result list.
        val restoreMethod = LuaLoaderRestoreFingerprint.method

        // Locate `invoke-direct {vX}, Ljava/util/ArrayList;-><init>()V` — the
        // construction of the list captured by both response listeners ($6/$7).
        // Injecting right after it means every later consumer sees the forged
        // rows alongside whatever Play actually returns.
        val listInitIndex = restoreMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_DIRECT &&
                getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Ljava/util/ArrayList;" && ref.parameterTypes.isEmpty()
                } ?: false
        }

        val injection = buildString {
            FREE_PRODUCT_IDS.forEach { productId ->
                append(forgedPurchaseBlock(productId))
                append('\n')
            }
        }
        restoreMethod.addInstructions(listInitIndex + 1, injection)
    }
}

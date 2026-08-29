package app.fancypants.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * OTTBilling.isPurchased(String) — single source-of-truth entitlement check for
 * all four Google Play SKUs (fancy_pants_premium, fancy_pants_wardrobe,
 * fancy_pants_world1, fancy_pants_world2).
 *
 * Confirmed smali (classes3/com/overthetopgames/fancypants/util/OTTBilling.smali
 * lines 612-641):
 *   .method public isPurchased(Ljava/lang/String;)Z
 *     .registers 4
 *     iget-object v0, p0, Lcom/overthetopgames/fancypants/util/OTTBilling;->skuStates:Ljava/util/HashMap;
 *     invoke-virtual {v0, p1}, Ljava/util/HashMap;->containsKey(Ljava/lang/Object;)Z
 *     move-result v0
 *     const/4 v1, 0x0
 *     if-eqz v0, :cond_14
 *     iget-object v0, p0, Lcom/overthetopgames/fancypants/util/OTTBilling;->skuStates:Ljava/util/HashMap;
 *     invoke-virtual {v0, p1}, Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
 *     move-result-object p1
 *     sget-object v0, Lcom/overthetopgames/fancypants/util/OTTBilling$SkuState;->SKU_STATE_PURCHASED_AND_ACKNOWLEDGED:Lcom/overthetopgames/fancypants/util/OTTBilling$SkuState;
 *     if-ne p1, v0, :cond_14
 *     const/4 v1, 0x1
 *     :cond_14
 *     return v1
 *   .end method
 */
object IsPurchasedFingerprint : Fingerprint(
    definingClass = "Lcom/overthetopgames/fancypants/util/OTTBilling;",
    name = "isPurchased",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        // reads the skuStates HashMap (iget-object)
        fieldAccess(
            smali = "Lcom/overthetopgames/fancypants/util/OTTBilling;" +
                "->skuStates:Ljava/util/HashMap;"
        ),
        // HashMap.containsKey(Object)Z
        methodCall(
            definingClass = "Ljava/util/HashMap;",
            name = "containsKey",
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Z"
        ),
        // HashMap.get(Object)Object
        methodCall(
            definingClass = "Ljava/util/HashMap;",
            name = "get",
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;"
        ),
        // compares against SKU_STATE_PURCHASED_AND_ACKNOWLEDGED (sget-object)
        fieldAccess(
            smali = "Lcom/overthetopgames/fancypants/util/OTTBilling\$SkuState;" +
                "->SKU_STATE_PURCHASED_AND_ACKNOWLEDGED:" +
                "Lcom/overthetopgames/fancypants/util/OTTBilling\$SkuState;"
        )
    )
)

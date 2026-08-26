package app.haak.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * HAAK Unity billing bridge — failure-callback methods in com.blingame.haakg.MainActivity
 * (smali/classes/com/blingame/haakg/MainActivity.smali, DEX classes/, v1.4.3).
 *
 * The game routes every Google Play Billing outcome to Unity through a single call shape:
 *
 *   .method <Name>(Ljava/lang/String;)V      # package-private, .registers 4 → p0=this(v2), p1=productId(v3)
 *       sget-object v0, Lcom/blingame/haakg/MainActivity;->gameObjectName:Ljava/lang/String;  # "steamworksMgr"
 *       const-string v1, "<unityCallback>"
 *       invoke-static {v0, v1, p1}, Lcom/unity3d/player/UnityPlayer;->UnitySendMessage(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
 *       return-void
 *
 * The five bridge methods (PaySuccess/PayFail/PayFailOnQuery/QuerySuccess/QueryFail) are
 * byte-identical except the const-string callback name — a Java↔IL2CPP contract that is
 * never renamed, making it the strongest anchor.
 *
 * Verified smali (v1.4.3):
 *   PayFail          line 229  const-string "payFailCallBack"        line 235
 *   PayFailOnQuery   line 242  const-string "payFailOnQueryCallBack" line 248
 *   PaySuccess       line 255  const-string "paySuccessCallBack"     line 261
 *   QueryFail        line 292  const-string "queryFailCallBack"      line 298
 *   QuerySuccess     line 423  const-string "querySuccessCallBack"   line 429
 *
 * Uniqueness: rg over the ENTIRE smali tree shows these strings occur only in
 * MainActivity.smali, exactly once each. Filter order below matches smali instruction
 * order (const-string precedes the UnitySendMessage invoke). Method names are NOT used
 * as fingerprint params — the non-obfuscated names are stable, but the string anchor is
 * the documented strategy and pins each method on its own.
 */

/**
 * PayFail(Ljava/lang/String;)V — fires on user-cancel (responseCode 1), billing errors,
 * and any non-OK/non-already-owned responseCode from MainActivity$1.onPurchasesUpdated.
 */
object PayFailFingerprint : Fingerprint(
    definingClass = "Lcom/blingame/haakg/MainActivity;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("payFailCallBack"),
        methodCall(
            definingClass = "Lcom/unity3d/player/UnityPlayer;",
            name = "UnitySendMessage"
        )
    )
)

/**
 * PayFailOnQuery(Ljava/lang/String;)V — fires when the product-details query fails during
 * a purchase attempt (MainActivity$3.onProductDetailsResponse, non-OK responseCode other
 * than already-owned code 7).
 */
object PayFailOnQueryFingerprint : Fingerprint(
    definingClass = "Lcom/blingame/haakg/MainActivity;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("payFailOnQueryCallBack"),
        methodCall(
            definingClass = "Lcom/unity3d/player/UnityPlayer;",
            name = "UnitySendMessage"
        )
    )
)

/**
 * QueryFail(Ljava/lang/String;)V — fires when the ownership query reports the product as
 * NOT purchased (MainActivity$4.onQueryPurchasesResponse else-branch). Redirecting this
 * also covers the restore-check path: "not purchased" becomes a grant.
 */
object QueryFailFingerprint : Fingerprint(
    definingClass = "Lcom/blingame/haakg/MainActivity;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string("queryFailCallBack"),
        methodCall(
            definingClass = "Lcom/unity3d/player/UnityPlayer;",
            name = "UnitySendMessage"
        )
    )
)

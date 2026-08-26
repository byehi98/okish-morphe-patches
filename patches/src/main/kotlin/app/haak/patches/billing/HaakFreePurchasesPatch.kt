package app.haak.patches.billing

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.haak.patches.shared.Constants.COMPATIBILITY_HAAK

// Success-twin method refs (same class, same (Ljava/lang/String;)V signature). No '$' in
// either descriptor, so no Kotlin escaping needed. Verified against
// analysis/com.blingame.haaktestb/smali/classes/com/blingame/haakg/MainActivity.smali:
//   PaySuccess   line 255 (.registers 4)  QuerySuccess line 423 (.registers 4)
private const val PAY_SUCCESS_REF =
    "Lcom/blingame/haakg/MainActivity;->PaySuccess(Ljava/lang/String;)V"
private const val QUERY_SUCCESS_REF =
    "Lcom/blingame/haakg/MainActivity;->QuerySuccess(Ljava/lang/String;)V"

/**
 * HAAK Free Purchases and Unlocked Paid Content (Option A — redirect failure
 * callbacks to success twins).
 *
 * The entire Google Play Billing flow runs through unobfuscated bridge methods in
 * com.blingame.haakg.MainActivity. Unity initiates purchases via SearchAndPurchaseById;
 * results come back through UnityPlayer.UnitySendMessage("steamworksMgr", <callback>,
 * productId). Failure paths normally notify Unity of cancel / error / not-purchased:
 *
 *   PayFail          ← user cancels sheet, billing error, bad responseCode ($1)
 *   PayFailOnQuery   ← product query fails during purchase ($3)
 *   QueryFail        ← ownership query says "not purchased" ($4)
 *
 * Each of the three gets its body short-circuited to delegate to its success twin:
 *   PayFail(p1)        → PaySuccess(p1)
 *   PayFailOnQuery(p1) → PaySuccess(p1)
 *   QueryFail(p1)      → QuerySuccess(p1)
 *
 * so every failure path grants the product instead. The real billing flow stays intact
 * (lowest divergence); genuine purchases and startup restore are untouched — they already
 * route to the success twins.
 *
 * Register budget: targets are `.registers 4` with two params → p0=this(v2), p1=String(v3);
 * `invoke-virtual {p0, p1}` + `return-void` fits without touching locals v0/v1. Everything
 * after the injected return-void is the original single-send body, left in place as
 * unreachable dead code (standard short-circuit pattern).
 *
 * Risk note (from findings): no Java-layer server validation exists; grants are local-trust.
 */
@Suppress("unused")
val haakFreePurchasesPatch = bytecodePatch(
    name = "HAAK Free Purchases and Unlocked Paid Content",
    description = "Redirects purchase failure callbacks to their success twins: cancelling " +
        "the Google Play dialog, a billing error, or an \"not purchased\" query result all " +
        "grant the product instead.",
    default = true
) {
    compatibleWith(COMPATIBILITY_HAAK)

    execute {
        // PayFail(productId) → PaySuccess(productId)
        PayFailFingerprint.method.addInstructions(0, """
            invoke-virtual {p0, p1}, $PAY_SUCCESS_REF
            return-void
        """.trimIndent())

        // PayFailOnQuery(productId) → PaySuccess(productId)
        PayFailOnQueryFingerprint.method.addInstructions(0, """
            invoke-virtual {p0, p1}, $PAY_SUCCESS_REF
            return-void
        """.trimIndent())

        // QueryFail(productId) → QuerySuccess(productId)
        QueryFailFingerprint.method.addInstructions(0, """
            invoke-virtual {p0, p1}, $QUERY_SUCCESS_REF
            return-void
        """.trimIndent())
    }
}

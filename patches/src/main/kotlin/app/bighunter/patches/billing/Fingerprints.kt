package app.bighunter.patches.billing

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * IAP chokepoint for Big Hunter 3.1.1 (classes2.dex, non-obfuscated).
 *
 * KKJPaymentGoogle is the game's entire Play Billing bridge. Its own "confirmed purchase"
 * path (used by the Already-Purchased -> YES dialog) skips Google entirely:
 *   addAndSaveUserPurchasedKey(key)          — persists to SharedPreferences __SP/__upk
 *   buyedProductKeyList.add(key)
 *   notifyPurchased()                        — private; 500ms later applies products
 *     -> applyPurchasedProducts() -> static native applyProduct("k1|k2") -> cocos grants
 *
 * startPurchase(String key): .registers 10 (v0-v7 locals, v8=p0/this, v9=p1/key).
 * The key arrives from native, so no SKU harvesting is needed.
 */
object StartPurchaseFingerprint : Fingerprint(
    definingClass = "LkakarodJavaLibs/data/KKJPaymentGoogle;",
    name = "startPurchase",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC),
)

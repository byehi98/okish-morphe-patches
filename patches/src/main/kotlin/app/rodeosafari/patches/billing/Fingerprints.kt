package app.rodeosafari.patches.billing

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Yodo1Purchase.pay(Activity, String, double, String) — the single funnel the Unity C# bridge
 * (UnityYodo1Payment.purchase) calls into whenever the player initiates a purchase.
 *
 * Confirmed smali (classes9.dex, line 95): public static pay(Landroid/app/Activity;Ljava/lang/String;DLjava/lang/String;)V
 *   - Sets callTime then forwards to Yodo1PayHelper.a(Activity, String, double, String).
 *   - NOT R8-obfuscated: Yodo1Purchase is the public MAS SDK entry point loaded reflectively by Unity.
 *   - UnityFlag$UnitySend.setObjectName/setMethodName are set by the CALLER (UnityYodo1Payment.purchase)
 *     immediately BEFORE this method, so firing the success callback here routes 2001 back to C# correctly.
 *
 * Stable filter: log string "call Yodo1PurchaseLocal pay ..." — the other pay overloads
 * (2-arg / 3-arg / string variants) are disambiguated by the parameter list.
 */
object Yodo1PurchasePayFingerprint : Fingerprint(
    definingClass = "Lcom/yodo1/android/sdk/open/Yodo1Purchase;",
    name = "pay",
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", "Ljava/lang/String;", "D", "Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        string("call Yodo1PurchaseLocal pay ...")
    )
)

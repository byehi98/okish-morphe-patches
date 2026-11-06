package app.dumbbellworkout.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The single paid product of Dumbbell Workout at Home 1.3.6.
 * Store metadata: one non-consumable in-app SKU, no subscription tier.
 */
private const val REMOVE_ADS_SKU = "dumbbellworkout.dumbbellapp.homeworkout.remove_ads"

/**
 * `Lnm3;->a(Ljava/lang/String;)Z` — the entitlement lookup.
 *
 * Verified in smali (`smali/classes/nm3.smali:1842`):
 *
 * ```
 * .method public final a(Ljava/lang/String;)Z
 *     .registers 2
 *     invoke-virtual {p0}, Lnm3;->b()V
 *     invoke-static {}, Lnm3;->c()Ljava/util/List;
 *     move-result-object p0
 *     invoke-interface {p0, p1}, Ljava/util/List;->contains(Ljava/lang/Object;)Z
 *     move-result p0
 *     if-nez p0, :cond_1c          # not owned → true
 * ```
 *
 * SEMANTICS ARE INVERTED: it returns **true when the SKU is NOT owned**, i.e.
 * true = "ads enabled / not purchased". The Java source
 * (`p000/nm3.java:318`) is
 * `return !(inapp().contains(sku) || subs().contains(sku))`.
 *
 * MATCH STRATEGY — structural, no obfuscated names:
 *  - `returnType = "Z"`, one `String` parameter, `public final` (instance).
 *  - the method calls `List.contains(Object)` — the entitlement test itself.
 *  - the defining class must contain both purchase-preference keys
 *    (`inapp_purchase_data`, `subs_purchase_data`), which only this class has.
 * R8 renames the class/method every release, so neither is pinned here; the
 * class-shape check is what keeps the fingerprint stable across versions.
 */
object IapEntitlementFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = "Ljava/util/List;", name = "contains"),
    ),
    custom = { _, classDef ->
        classDef.containsString("inapp_purchase_data") &&
            classDef.containsString("subs_purchase_data")
    },
)

/**
 * `Lyb;->c()Z` — the global "should this build show ads?" gate.
 *
 * Verified in smali (`smali/classes3/yb.smali:2070`):
 *
 * ```
 * .method public static final c()Z
 *     .registers 2
 *     sget-object v0, Lnm3;->a:Lnm3;
 *     const-string v1, "dumbbellworkout.dumbbellapp.homeworkout.remove_ads"
 *     invoke-virtual {v0, v1}, Lnm3;->a(Ljava/lang/String;)Z
 * ```
 *
 * It is a thin delegate to [IapEntitlementFingerprint], so it inherits the same
 * inverted semantics. 14 call sites across the ad wrappers
 * (`Lem4;` banner, `Ljm4;` interstitial, `Llm4;` native, `Lnm4;` app-open,
 * `Lzy6;` splash) consult it; rewarded ads (`Lpm4;`) are deliberately not gated
 * by it and are left untouched.
 *
 * MATCH STRATEGY: the remove_ads SKU literal plus the `Z`-returning delegate,
 * both statically visible, so no obfuscated name is pinned.
 */
object AdsEnabledFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    filters = listOf(
        string(REMOVE_ADS_SKU),
        methodCall(name = "a", returnType = "Z"),
    ),
)

/**
 * True when any method in this class loads a string literal containing [needle].
 *
 * Used as a class-shape anchor instead of hardcoding the R8-renamed class name.
 */
private fun ClassDef.containsString(needle: String): Boolean =
    methods.any { method ->
        method.implementation?.instructions?.any { instruction ->
            instruction is ReferenceInstruction &&
                (instruction.reference as? StringReference)?.string?.contains(needle) == true
        } == true
    }

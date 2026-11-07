package app.dumbbellworkout.patches.premium

import app.dumbbellworkout.patches.shared.Constants.COMPATIBILITY_DUMBBELL_WORKOUT
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly

/**
 * Dumbbell Workout at Home — Remove Ads / Ad-Free Unlock
 * (`dumbbellworkout.dumbbellapp.homeworkout` 1.3.6 / versionCode 52).
 *
 * ## What the entitlement actually is
 *
 * The app has exactly one paid product, the non-consumable
 * `dumbbellworkout.dumbbellapp.homeworkout.remove_ads`. There is no subscription
 * tier and no premium content: "Remove Ads" **is** the whole paid offering. All
 * purchase state lives in `SharedPreferences("iap_sp")` as two JSON arrays,
 * `inapp_purchase_data` and `subs_purchase_data` (the latter is never populated —
 * `mm3.f60756c` is empty), and is read through one method:
 *
 * ```
 * Lnm3;->a(Ljava/lang/String;)Z   // true = NOT owned (ads enabled)
 * ```
 *
 * 14 ad call sites route through `Lyb;->c()Z`, a thin delegate to that method,
 * covering banners, natives, interstitials, app-open ads and the splash ad.
 * Forcing the entitlement method to return `false` is therefore equivalent to a
 * completed purchase: every ad format stops and the "Remove ads" upsell row
 * disappears from settings (`Ltp6;->H0()` / `Ltp6;->c0()` consult the same gate).
 *
 * ## Why `false` and not `true`
 *
 * The naming is misleading: `a()` returns `!("inapp".contains(sku) ||
 * "subs".contains(sku))`. Returning `true` would mean "not purchased" and keep
 * every ad. Both targets here return **false**.
 *
 * ## Register safety
 *
 * `Lnm3;->a` is an instance method with `.registers 2` and two parameters
 * (`this`, `String`), so it has no spare locals and `v0 == p0`. `returnEarly`
 * injects `const/4 v0, 0x0` + `return v0` at index 0: writing `v0` clobbers the
 * SKU parameter, but the method returns immediately, so the original body (which
 * would have used it) is never reached. `Lyb;->c()` is static with
 * `.registers 2` and no parameters, so `v0` is a plain local there.
 *
 * ## Dependencies
 *
 * This patch is only useful together with **Signature Spoof**: a re-signed APK
 * cannot start at all (Java 38-reader verifier + native `liblpcore.so`
 * `JNI_OnLoad`), so shipping Remove Ads without it produces an APK that shows the
 * "File corrupted" dialog before any screen loads.
 */
@Suppress("unused")
val dumbbellRemoveAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Unlocks the paid \"Remove ads\" product locally, so the app behaves as if it " +
        "was purchased: banners, native, interstitial, app-open and splash ads stop, and the " +
        "\"Remove ads\" row disappears from settings. No Play payment is involved and the " +
        "entitlement survives restarts. Rewarded ads are left untouched. Requires the Signature " +
        "Spoof patch in the same bundle for the patched APK to start. " +
        "Google Sign-In / Backup & Restore does not work on a re-signed APK: the app's OAuth " +
        "client is bound to the developer's signing certificate and Google refuses token " +
        "issuance server-side, so no microG-style patch or signature spoof can bypass it. " +
        "Use the unpatched app if you need cloud sync.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DUMBBELL_WORKOUT)

    execute {
        val entitlement = IapEntitlementFingerprint.matchAll()
        if (entitlement.size != 1) {
            throw PatchException(
                "Remove Ads: expected exactly one entitlement method" +
                    " (public final Z(String) calling List.contains in the class holding" +
                    " iap_sp keys), found ${entitlement.size}:" +
                    entitlement.joinToString(prefix = " ", separator = ", ") { it.method.toString() } +
                    ". The purchase model changed — refusing to emit a patch that would not" +
                    " disable ads."
            )
        }
        entitlement.single().method.returnEarly(false)

        val adsEnabled = AdsEnabledFingerprint.matchAll()
        if (adsEnabled.size != 1) {
            throw PatchException(
                "Remove Ads: expected exactly one ads-enabled gate" +
                    " (static final Z() containing the remove_ads SKU), found ${adsEnabled.size}:" +
                    adsEnabled.joinToString(prefix = " ", separator = ", ") { it.method.toString() } +
                    ". The ad gate changed — refusing to emit a patch that would not disable ads."
            )
        }
        adsEnabled.single().method.returnEarly(false)
    }
}

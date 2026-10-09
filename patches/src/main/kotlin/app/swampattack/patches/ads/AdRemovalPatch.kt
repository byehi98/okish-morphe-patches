package app.swampattack.patches.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.swampattack.patches.shared.Constants.COMPATIBILITY_SWAMP_ATTACK

/**
 * Swamp Attack 4.8.7.0 · **Remove Ads** — forced (interstitial) ads never load or show.
 *
 * Forces the app's own permanent-removal gate: `AdManager.areAdsPermanentlyRemoved(Context)`
 * always returns `true`. Every interstitial call site in `AdManager` branches on it first:
 *
 * ```
 * loadInterstitial()  → return   (no ad ever fetched)
 * showInterstitial()  → return   (nothing ever displayed)
 * onResume()          → skips interstitial reload (rewarded reload unaffected)
 * ```
 *
 * This is the flag a real "remove ads" purchase ends up setting — normally written by
 * `NativeInterface` (jadx line 264) only when native reports the purchase granted ad
 * removal AND remote config `timed_ad_removal_on_purchase` is OFF. Because that remote
 * config can instead enable a **7-day** removal window, forcing the gate directly is the
 * only way to guarantee *permanent* ad-free, and it works regardless of what the server
 * config says — no network, no purchase, no mediation SDK involvement.
 *
 * Deliberately scoped:
 * * Rewarded video is intentionally NOT gated by the app here (opt-in reward ads still
 *   work); this patch mirrors exactly the app's own semantics rather than going broader.
 *   A full rewarded-ad block would be a separate opt-in patch.
 * * Pure one-method DEX change — no resource/native edits, disjoint from
 *   `../billing/FreeStorePatch.kt` (different classes, no shared offsets; safe together).
 *
 * Anchors: analysis/swampattack/notes/iap-analysis.md (ad layer section).
 */
@Suppress("unused")
val swampAttackRemoveAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Forced ads are gone for good — interstitials never load or show, " +
        "even offline and regardless of server settings. Rewarded videos you choose to " +
        "watch still work.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SWAMP_ATTACK)

    execute {
        AreAdsPermanentlyRemovedFingerprint.method.returnEarly(true)
    }
}

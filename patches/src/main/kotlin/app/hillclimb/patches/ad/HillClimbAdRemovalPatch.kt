package app.hillclimb.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.hillclimb.patches.shared.Constants.COMPATIBILITY_HILLCLIMB

/**
 * Hill Climb Racing — Complete Ad Removal (banners + interstitials + ad-free)
 *
 * Three layers, all verified against classes3.dex smali:
 *
 * 1. Ad-free status: seeded ONCE per app start in
 *    InAppPurchaseStore.loadStore(Context)V (InAppPurchaseStore.smali:844).
 *    The native C++ engine (libgame.so) polls the ad-free flag continuously
 *    via the JNI export MainActivity.getIAPAdFree()I (MainActivity.smali:1001),
 *    which delegates to InAppPurchaseStore.getAdFree(Context)I.
 *    getAdFree is left COMPLETELY untouched — it still reads the store's
 *    mAdFree field and returns 0 while a purchase is in-flight (mModified).
 *    Instead, right after loadStore reads the pending amount from the "adfree"
 *    pref into mAdFree, we overwrite it with 1 (injected after the mAdFree sput,
 *    which sits at instruction index 33 of the original smali):
 *
 *      const v2, 0x1
 *      sput v2, Lcom/fingersoft/game/InAppPurchaseStore;->mAdFree:I
 *
 *    Register safety: at that point v2 holds the just-loaded pref int and is
 *    dead until the next move-result-object overwrites it, so clobbering it is
 *    safe (same reasoning as the currency patch's mCoins/mGems seeds).
 *
 *    The engine then grants ad-free exactly once (one "purchase complete"
 *    popup) and its own consume cycle — inappPurchasesProcessed() zeroes
 *    mAdFree and clears mModified (InAppPurchaseStore.smali:756) — so every
 *    later poll returns 0 and the popup never repeats.
 *
 *    (This mirrors the proven currency patch. The OLD approach forced
 *    getAdFree to always return 1, bypassing the store entirely; the reset
 *    never applied and every continuous poll re-granted ad-free → the
 *    "purchase complete" popup spammed forever — the exact regression this
 *    patch fixes.)
 *
 * 2. Banner suppression: CFirebaseAds.showBanners()V is a no-op (return-void).
 *    CFirebaseBannerProvider.show(Z) is only ever called from this method, so
 *    no banner can be displayed. MainActivity.showBanners()V simply delegates.
 *
 * 3. Interstitial suppression: CFirebaseAds.showInterstitial(String)V is a
 *    no-op (return-void). CFirebaseInterstitial.show(String) is only ever
 *    called from this method, so no interstitial can be displayed. The JNI
 *    entry MainActivity.showInterstitialFromGame(...) simply delegates.
 *
 * Rewarded videos (showVideoAd) are deliberately LEFT untouched — the separate
 * "Hill Climb Racing Instant Rewarded Video Rewards" patch handles them by
 * granting the reward instantly instead of playing the ad.
 */
@Suppress("unused")
val hillClimbAdRemovalPatch = bytecodePatch(
    name = "Hill Climb Racing Ad Removal",
    description = "Completely removes ads: banners and interstitials can never be displayed (CFirebaseAds.showBanners/showInterstitial become no-ops) and ad-free is granted once per app start — loadStore() seeds mAdFree = 1, the native engine's poll grants it and the store's own reset (inappPurchasesProcessed) zeroes the field, so no repeated purchase popups.",
    default = true
) {
    compatibleWith(COMPATIBILITY_HILLCLIMB)

    execute {
        // Seed mAdFree = 1 right after loadStore reads it from the "adfree" pref
        // (instructionMatches[3] = sput v2, InAppPurchaseStore.mAdFree:I, index 33
        // in the original smali). The native engine grants ad-free once, then
        // inappPurchasesProcessed() zeroes mAdFree and clears mModified — every
        // later poll returns 0, so the "purchase complete" popup never spams.
        // getAdFree itself is deliberately left untouched.
        AdLoadStoreFingerprint.method.addInstructions(
            AdLoadStoreFingerprint.instructionMatches[3].index + 1,
            """
                const v2, 0x1
                sput v2, Lcom/fingersoft/game/InAppPurchaseStore;->mAdFree:I
            """.trimIndent()
        )

        ShowBannersFingerprint.method.returnEarly()
        ShowInterstitialFingerprint.method.returnEarly()
    }
}

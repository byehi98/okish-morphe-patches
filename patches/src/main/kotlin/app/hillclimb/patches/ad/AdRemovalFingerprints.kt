package app.hillclimb.patches.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * InAppPurchaseStore.getAdFree(Context)I — public static declared-synchronized.
 * Queries whether the user owns the Ad-Free feature.
 *
 * The native C++ engine (libgame.so) polls this continuously through the JNI
 * export MainActivity.getIAPAdFree()I (MainActivity.smali:1001), which simply
 * delegates: getApplicationContext → InAppPurchaseStore.getAdFree(Context).
 * getAdFree is NOT called anywhere else in the app.
 *
 * Confirmed smali: InAppPurchaseStore.smali:299 (classes3.dex).
 *
 * Caveat verified against smali: when the store is mid-purchase
 * (mModified == 1) the method bails out early and returns 0 — and the engine's
 * own consume cycle (inappPurchasesProcessed) zeroes mAdFree after each poll.
 * Forcing a constant return ignored both (the old approach), which caused the
 * "purchase complete" popup spam — see the NOTE below and AdLoadStoreFingerprint.
 *
 * Note on access flags: the method is ACC_DECLARED_SYNCHRONIZED (0x20000),
 * NOT ACC_SYNCHRONIZED (0x20). The Fingerprint accessFlags match is EXACT
 * (Fingerprint.kt compares the folded int), so listing AccessFlags.SYNCHRONIZED
 * would fail to resolve. baksmali prints every set flag, and the smali header
 * shows only "declared-synchronized" — hence the flag list below.
 *
 * Filters land in exact instruction order (0-based indices):
 *   0. const-string " stored ad-free" (index 20 — log suffix of the
 *      "Player received <n> stored ad-free" StringBuilder)
 *   1. Log.d(String,String)V          (index 25 — fsiap log of the stored value)
 *
 * NOTE: The Ad Removal patch no longer overrides this method (that caused the
 * "purchase complete" popup spam — the getter bypassed the store so the
 * engine's consume-cycle reset never applied). getAdFree is left untouched;
 * ad-free is instead seeded once in loadStore (see AdLoadStoreFingerprint).
 */
object GetAdFreeFingerprint : Fingerprint(
    definingClass = "Lcom/fingersoft/game/InAppPurchaseStore;",
    name = "getAdFree",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.DECLARED_SYNCHRONIZED),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string(" stored ad-free"),
        methodCall(definingClass = "Lcom/fingersoft/utils/Log;", name = "d")
    )
)

/**
 * InAppPurchaseStore.loadStore(Context)V — public static, single Context param.
 * Same method the currency patch's LoadStoreFingerprint
 * (app.hillclimb.patches.currency.Fingerprints.kt) targets, but this is a
 * SEPARATE object whose filters specifically anchor on the mAdFree sput so the
 * ad patch can seed mAdFree = 1 without touching getAdFree's smali at all.
 *
 * Why seeding here stops the popup spam: the native C++ engine (libgame.so)
 * polls the ad-free flag CONTINUOUSLY via MainActivity.getIAPAdFree()I
 * (MainActivity.smali:1001) → InAppPurchaseStore.getAdFree(Context)I. The old
 * patch forced getAdFree to always return 1, which bypassed the store — the
 * engine re-granted ad-free on every poll and showed the "purchase complete"
 * popup forever. By seeding mAdFree right after loadStore reads it from prefs,
 * the engine grants ad-free exactly once per app start; its own consume cycle
 * (inappPurchasesProcessed → sput mAdFree = 0, mModified = 0, see
 * InAppPurchaseStore.smali:756) then zeroes the field and every later poll
 * returns 0. No spam.
 *
 * Confirmed smali: InAppPurchaseStore.smali:844 (classes3.dex). Filters land
 * in exact instruction order (0-based indices inside the method):
 *   0. invoke-virtual Context.getSharedPreferences  (index 2  — "iap_db" call)
 *   1. sput v2, InAppPurchaseStore.mCoins:I         (index 15 — loaded from "numCoins")
 *   2. sput v2, InAppPurchaseStore.mGems:I          (index 21 — loaded from "numGems")
 *   3. sput v2, InAppPurchaseStore.mAdFree:I        (index 33 — loaded from "adfree")
 *   4. sput-boolean p0, ...mLoaded:Z                (index 77 — end-of-method flag)
 *
 * NOTE: index 27 is the mPaints sput; the mAdFree sput follows the numPaints
 * block at index 33. Each field is accessed exactly once in the method, so the
 * fieldAccess filters are unambiguous. The currency patch inserts its own
 * instructions after mCoins/mGems at execute time; because these filters are
 * field-based and matched in order, this fingerprint still resolves to the
 * mAdFree sput whether or not that patch ran first (the indices just shift by
 * the currency patch's +4). The patch inserts right after match [3] — v2 holds
 * the just-loaded pref int there and is dead until the next move-result-object
 * overwrites it, so clobbering it with const + sput is safe (same reasoning as
 * the currency patch's register safety).
 */
object AdLoadStoreFingerprint : Fingerprint(
    definingClass = "Lcom/fingersoft/game/InAppPurchaseStore;",
    name = "loadStore",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(definingClass = "Landroid/content/Context;", name = "getSharedPreferences"),
        fieldAccess(smali = "Lcom/fingersoft/game/InAppPurchaseStore;->mCoins:I"),
        fieldAccess(smali = "Lcom/fingersoft/game/InAppPurchaseStore;->mGems:I"),
        fieldAccess(smali = "Lcom/fingersoft/game/InAppPurchaseStore;->mAdFree:I"),
        fieldAccess(smali = "Lcom/fingersoft/game/InAppPurchaseStore;->mLoaded:Z")
    )
)

/**
 * CFirebaseAds.showBanners()V — public, no params. Displays the banner ad.
 *
 * Confirmed smali: CFirebaseAds.smali:630 (classes3.dex). It reads
 * mFirebaseBanner and, if non-null, calls CFirebaseBannerProvider.show(true).
 * CFirebaseBannerProvider.show(Z) is invoked from EXACTLY this one method, so
 * neutering showBanners kills the whole Java-side banner display path
 * (MainActivity.showBanners()V at MainActivity.smali:3312 merely delegates).
 *
 * Filters land in exact instruction order:
 *   0. iget-object p0, ...CFirebaseAds->mFirebaseBanner   (index 0)
 *   1. invoke-interface ...CFirebaseBannerProvider->show  (index 3)
 */
object ShowBannersFingerprint : Fingerprint(
    definingClass = "Lcom/fingersoft/game/firebase/CFirebaseAds;",
    name = "showBanners",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        fieldAccess(smali = "Lcom/fingersoft/game/firebase/CFirebaseAds;->mFirebaseBanner:Lcom/fingersoft/game/firebase/CFirebaseBannerProvider;"),
        methodCall(definingClass = "Lcom/fingersoft/game/firebase/CFirebaseBannerProvider;", name = "show")
    )
)

/**
 * CFirebaseAds.showInterstitial(String)V — public, one String param. Displays
 * an interstitial ad for the given ad unit.
 *
 * Confirmed smali: CFirebaseAds.smali:642 (classes3.dex). It reads
 * mFirebaseInterstitial and, if non-null, calls CFirebaseInterstitial.show(String).
 * CFirebaseInterstitial.show(Ljava/lang/String;)V is invoked from EXACTLY this
 * one method, so neutering showInterstitial kills the whole Java-side
 * interstitial display path (the JNI entry
 * MainActivity.showInterstitialFromGame(ILjava/lang/String;)V at
 * MainActivity.smali:3395 merely delegates here).
 *
 * Filters land in exact instruction order:
 *   0. iget-object p0, ...CFirebaseAds->mFirebaseInterstitial  (index 0)
 *   1. invoke-virtual ...CFirebaseInterstitial->show          (index 2)
 */
object ShowInterstitialFingerprint : Fingerprint(
    definingClass = "Lcom/fingersoft/game/firebase/CFirebaseAds;",
    name = "showInterstitial",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(smali = "Lcom/fingersoft/game/firebase/CFirebaseAds;->mFirebaseInterstitial:Lcom/fingersoft/game/firebase/CFirebaseInterstitial;"),
        methodCall(definingClass = "Lcom/fingersoft/game/firebase/CFirebaseInterstitial;", name = "show")
    )
)

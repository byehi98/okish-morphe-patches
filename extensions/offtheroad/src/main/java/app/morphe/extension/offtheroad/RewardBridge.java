package app.morphe.extension.offtheroad;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Runtime helper for the Off The Road "Free Rewarded Ads" patch.
 *
 * <p>The native reward sequence is order-sensitive:
 *
 * <pre>
 * cApplication::showRewardedVideoAd  (libgame.so 0x375794)
 *   - calls cAdmob::showRewarded / cUnityAds::showRewardedAd   (JNI → these wrappers)
 *   - mutes audio
 *   - THEN writes VREW_T / VREW_S / VREW_C
 *
 * cApplication::onRewardEarned → showClaimVideoAdRewardDialog → reads VREW_* back
 * </pre>
 *
 * <p>Invoking the reward callback synchronously from the wrapper would run before the
 * native {@code VREW_*} writes and the claim dialog would use stale values (zero on the
 * first reward). This bridge therefore posts the callbacks on the main looper, so the
 * whole native {@code showRewardedVideoAd} frame has unwound and the config keys are
 * correct by the time the reward is granted.
 *
 * <p>Called from smali injected by {@code offTheRoadFreeRewardedAdsPatch}. The wrapper
 * classes and their callback names are public, un-obfuscated app code:
 * {@code com.utils.Admob.callRewardEarned()/callAdFinished()} and
 * {@code com.utils.UnityAds.onRewardEarned()/onAdFinished()}.
 */
public final class RewardBridge {
    /** Native writes VREW_* immediately after the wrapper returns; 500 ms is ample. */
    private static final long CALLBACK_DELAY_MS = 500L;

    /** cGSSplash::update constructs cInappPurchase; wait until initPurchase() has returned. */
    private static final long VIP_DELAY_MS = 1500L;

    /** Suppresses a double grant if both show methods are reached for one request. */
    private static final long DEDUPE_WINDOW_MS = 1000L;

    /**
     * Note the store id prefix: the game uses {@code com.dogbyte.offtheroad.*} even
     * though the Android package is {@code com.dogbytegames.offtheroad}. The native
     * classifier in {@code cApplication::onItemPurchasedOrRestored} only matches the
     * short prefix.
     */
    private static final String VIP_SKU = "com.dogbyte.offtheroad.vip_club";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static long lastGrantAt = 0L;

    private static boolean vipGrantScheduled = false;

    private RewardBridge() {
    }

    /** AdMob path: {@code Admob.ShowRewarded()} was called instead of showing an ad. */
    public static void grantAdmobReward() {
        scheduleGrant("com.utils.Admob", "callRewardEarned", "callAdFinished");
    }

    /** Unity Ads path: {@code UnityAds.showRewardedAd()} was called instead of showing an ad. */
    public static void grantUnityReward() {
        scheduleGrant("com.utils.UnityAds", "onRewardEarned", "onAdFinished");
    }

    /**
     * VIP path: {@code InappPurchase.setNativeInit()} was called from the native
     * {@code cInappPurchase} constructor. The grant is deferred so it lands after
     * {@code cApplication::initPurchase} has registered its purchase event handlers
     * on the {@code cInappPurchase} event objects.
     *
     * <p>VIP status is not taken from the purchase callback itself: the native
     * {@code cApplication::onItemPurchasedOrRestored} branch for {@code vip_club}
     * only raises a "just purchased" flag and asks for a product-data refresh.
     * The actual VIP timestamp ({@code cApplication+0x478}, the value queried by
     * {@code cApplication::isVipClubActive}) is written by the handler of the
     * {@code cInappPurchase+0x60} event, which is raised by the native
     * {@code requestProductPurchasesCompleteCallback} worker after it copies each
     * product's purchase time from Java. That worker is only invoked when the Play
     * purchase query succeeds — on devices without Play (Waydroid, microG) it never
     * runs, and on normal devices it runs with an empty purchase list, so the VIP
     * timestamp stays 0 either way.
     *
     * <p>We therefore drive the completion callback ourselves with {@code true}.
     * Combined with the Java-side {@code getProductPurchaseTime} patch (which
     * reports a live timestamp for the VIP SKU) the native handler computes and
     * stores the VIP state, without needing Play, a purchase, or a receipt.
     */
    public static void grantVipDelayed() {
        synchronized (RewardBridge.class) {
            if (vipGrantScheduled) {
                return;
            }
            vipGrantScheduled = true;
        }

        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> iapClass = Class.forName("com.utils.InappPurchase");

                    Method getInstance = iapClass.getDeclaredMethod("getInstance");
                    getInstance.setAccessible(true);
                    Object instance = getInstance.invoke(null);
                    if (instance == null) {
                        return;
                    }

                    Method refresh = iapClass.getDeclaredMethod(
                            "requestProductPurchasesCompleteCallback", boolean.class);
                    refresh.setAccessible(true);
                    refresh.invoke(instance, true);
                } catch (Throwable ignored) {
                    // Never let the helper crash the game.
                }
            }
        }, VIP_DELAY_MS);
    }

    private static void scheduleGrant(
            final String className,
            final String rewardMethodName,
            final String finishedMethodName
    ) {
        // Claim the slot before posting, so a second call in the same frame is dropped.
        synchronized (RewardBridge.class) {
            long now = System.currentTimeMillis();
            if (now - lastGrantAt < DEDUPE_WINDOW_MS) {
                return;
            }
            lastGrantAt = now;
        }

        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> wrapperClass = Class.forName(className);

                    Method getInstance = wrapperClass.getDeclaredMethod("getInstance");
                    getInstance.setAccessible(true);
                    Object instance = getInstance.invoke(null);
                    if (instance == null) {
                        return;
                    }

                    // Reward first (opens the claim dialog), then finished (unmutes audio
                    // and resets the ad cooldown) — same order as the real SDK listener.
                    invoke(instance, wrapperClass, rewardMethodName);
                    invoke(instance, wrapperClass, finishedMethodName);
                } catch (Throwable ignored) {
                    // Never let the helper crash the game; a missed reward is recoverable.
                }
            }
        }, CALLBACK_DELAY_MS);
    }

    private static void invoke(Object instance, Class<?> wrapperClass, String methodName)
            throws Exception {
        Method method = wrapperClass.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(instance);
    }
}

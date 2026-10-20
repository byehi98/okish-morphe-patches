package app.morphe.extension.pvzfree;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Runtime helper for the PvZ FREE ad patch.
 *
 * <p>The GluAds ironSource managers are neutralised in bytecode: {@code load()} and
 * {@code show()} return immediately. However, the native game (libpvz) drives those
 * methods and waits for the corresponding {@code IAdvertisingListener} placement
 * events. Emitting those events <em>synchronously from inside</em> {@code load()}
 * / {@code show()} re-enters the native state machine before the caller has finished
 * updating its own state (the native caller sets "ad in progress" only after
 * {@code show()} returns). The result is a permanently stuck level intro.
 *
 * <p>This class re-emits the same events, through the manager's own
 * {@code sendPlacementEvent(...)}, but posted to the main looper so they are always
 * delivered after the native call has returned — exactly like the real ironSource
 * SDK callbacks.
 *
 * <p>Reflection is used because {@code sendPlacementEvent} is protected, {@code
 * sendReward} is private, and the extension is compiled without the app's classes
 * on its classpath.
 */
public final class AdsHelper {
    private static final String TAG = "PvZAds";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String STATUS_SHOW_STARTED = "showStarted";
    private static final String STATUS_SHOW_FINISHED = "showFinished";
    private static final String STATUS_LOAD_FINISHED = "loadFinished";

    private AdsHelper() {
    }

    /** Reports a successful load without touching the network. */
    public static void fakeLoad(Object manager, String placement) {
        MAIN.postDelayed(() -> emit(manager, placement, STATUS_LOAD_FINISHED), 50L);
    }

    /** Reports a full interstitial impression without ever showing one. */
    public static void fakeInterstitial(Object manager, String placement) {
        MAIN.postDelayed(() -> emit(manager, placement, STATUS_SHOW_STARTED), 100L);
        MAIN.postDelayed(() -> emit(manager, placement, STATUS_SHOW_FINISHED), 600L);
    }

    /** Reports a rewarded impression and grants the configured reward without a video. */
    public static void fakeRewarded(Object manager, String adType, String placement) {
        MAIN.postDelayed(() -> emit(manager, placement, STATUS_SHOW_STARTED), 100L);
        MAIN.postDelayed(() -> grantReward(manager, adType, placement), 400L);
        MAIN.postDelayed(() -> emit(manager, placement, STATUS_SHOW_FINISHED), 700L);
    }

    /**
     * Mirrors {@code EAIronSourceRVManager.sendReward(item, amount, placement)} using the
     * reward configured for this placement, falling back to a single coin so the native
     * {@code RewardedVideoManager} still fires.
     */
    private static void grantReward(Object manager, String adType, String placement) {
        try {
            String item = "coins";
            int amount = 1;

            Object reward = method(manager, "getReward", String.class, String.class)
                    .invoke(manager, adType, placement);
            if (reward != null) {
                Object configuredItem = reward.getClass().getField("item").get(reward);
                if (configuredItem instanceof String && !((String) configuredItem).isEmpty()) {
                    item = (String) configuredItem;
                }
                amount = reward.getClass().getField("amount").getInt(reward);
            }

            method(manager, "sendReward", String.class, int.class, String.class)
                    .invoke(manager, item, amount, placement);
            Log.d(TAG, "granted " + amount + " " + item + " for " + placement);
        } catch (Throwable t) {
            Log.e(TAG, "grantReward failed for " + placement, t);
        }
    }

    /** Calls the manager's protected {@code sendPlacementEvent(placement, status, error, extra)}. */
    private static void emit(Object manager, String placement, String status) {
        try {
            method(manager, "sendPlacementEvent",
                    String.class, String.class, Throwable.class, Map.class)
                    .invoke(manager, placement, status, null, null);
            Log.d(TAG, "emitted " + status + " for " + placement);
        } catch (Throwable t) {
            Log.e(TAG, "emit " + status + " failed for " + placement, t);
        }
    }

    private static Method method(Object target, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Keep walking up the hierarchy.
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }
}

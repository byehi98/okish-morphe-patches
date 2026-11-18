package app.morphe.extension.injustice;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime helper for the Injustice: Gods Among Us billing patches.
 *
 * <p>Why this exists: the game asks Google Play Billing for the store catalog and for a
 * successful billing-client connection. A patched (re-signed) APK is not the build that
 * is registered in Play, so the Billing client answers {@code DEVELOPER_ERROR} /
 * {@code BILLING_UNAVAILABLE}: {@code GooglePlayStoreHelper$2.onBillingSetupFinished}
 * never calls the setup listener (so the native store never receives
 * {@code NativeCallback_MicroTransactionOnInit}) and
 * {@code MicroTransactionAndroid.refreshProductList} ends up in the failure branch
 * ({@code NativeCallback_MicroTransactionOnGetAvailableProductsFailed}). The native
 * store then reports that it cannot connect to Google Play and never reaches
 * {@code JavaCallback_MicroTransactionBeginPurchase} — the method the existing patch
 * already rewrites to grant the item.
 *
 * <p>This bridge closes both gaps on the Java side, without touching the Play dialog:
 * <ul>
 *   <li>{@link #forceSetup(Object)} marks the billing helper as connected and invokes the
 *       captured {@code HelperSetupFinishedListener}, which queues
 *       {@code NativeCallback_MicroTransactionOnInit}.</li>
 *   <li>{@link #queueProductList(Object, String[])} builds a synthetic
 *       {@code ProductDetails} catalog for exactly the SKUs the native store requested and
 *       queues a real {@code ProductListCommand}, so the storefront receives its full
 *       product/price table and unlocks the Buy buttons.</li>
 * </ul>
 *
 * <p>Everything is reflective because the extension is compiled without the app classes.
 * The target names are stable, non-obfuscated platform code in 3.5.1.
 */
public final class StoreBridge {

    private static final String STORE_ANDROID = "macrotransaction.google.MicroTransactionAndroid";
    private static final String INVENTORY = "macrotransaction.google.util.Inventory";
    private static final String PRODUCT_DETAILS = "com.android.billingclient.api.ProductDetails";
    private static final String SKU_PREFIX = "com.wb.injustice";
    private static final String TAG = "MorpheInjustice";

    /** The helper setup listener we have already forced; guards against a double init. */
    private static volatile Object forcedSetupListener;

    /** True once a {@code SetupFinishedCommand} has been queued (which delivers OnInit). */
    private static volatile boolean initQueued;

    private StoreBridge() {
    }

    // ------------------------------------------------------------------
    // 0 — Store init: deliver NativeCallback_MicroTransactionOnInit.
    // ------------------------------------------------------------------

    /**
     * Called from the entry of {@code MicroTransactionAndroid.Init(Activity)}.
     *
     * <p>Native {@code UMicroTransactionAndroid::Init} sets its "purchases blocked" byte
     * ({@code +0xc4}) to 1 and then calls Java {@code Init}. Only
     * {@code NativeCallback_MicroTransactionOnInit} clears that byte, so until OnInit is
     * delivered the storefront keeps the "Cannot Connect To Google Play" banner on every
     * tile and {@code GET} does nothing. On devices where the Play Billing callback never
     * arrives (no Play services, billing service not bound, …) nothing would ever deliver
     * it, so this queues the app's own {@code SetupFinishedCommand} — the exact same
     * command {@code HelperSetupFinishedListener.onHelperSetupFinished()} queues on a
     * successful billing connection.
     *
     * <p>Queueing (instead of calling the native callback inline) keeps the callback on
     * the game thread, inside {@code MicroTransactionAndroid.update()}.
     */
    public static void ensureInit(Object microTransactionAndroid) {
        if (initQueued || microTransactionAndroid == null) {
            return;
        }
        synchronized (StoreBridge.class) {
            if (initQueued) {
                return;
            }
            try {
                Class<?> storeClass = Class.forName(STORE_ANDROID);
                Class<?> markerClass = Class.forName(STORE_ANDROID + "$1");
                Class<?> commandClass = Class.forName(STORE_ANDROID + "$SetupFinishedCommand");
                Constructor<?> commandCtor = commandClass.getDeclaredConstructor(storeClass, markerClass);
                commandCtor.setAccessible(true);
                Object command = commandCtor.newInstance(microTransactionAndroid, null);
                if (offer(command)) {
                    initQueued = true;
                    Log.d(TAG, "ensureInit: SetupFinishedCommand queued");
                }
            } catch (Throwable t) {
                Log.e(TAG, "ensureInit failed", t);
            }
        }
    }

    /** Offers a command to {@code MicroTransactionAndroid.mCommandQueue}. */
    private static boolean offer(Object command) {
        try {
            Object queue = commandQueue(Class.forName(STORE_ANDROID));
            if (queue == null) {
                return false;
            }
            Method offer = queue.getClass().getMethod("offer", Object.class);
            offer.setAccessible(true);
            return Boolean.TRUE.equals(offer.invoke(queue, command));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 1 — Billing client connection: always report "connected".
    // ------------------------------------------------------------------

    /**
     * Called from the entry of {@code GooglePlayStoreHelper$2.onBillingSetupFinished}.
     *
     * @param billingStateListener the anonymous {@code BillingClientStateListener}
     *                             ({@code GooglePlayStoreHelper$2}) whose callback we are in.
     */
    public static boolean forceSetup(Object billingStateListener) {
        if (billingStateListener == null) {
            return false;
        }
        // ensureInit already delivered OnInit; keep the stock failure branch out of the way.
        if (initQueued) {
            return true;
        }
        if (billingStateListener == forcedSetupListener) {
            return true;
        }
        synchronized (StoreBridge.class) {
            if (initQueued || billingStateListener == forcedSetupListener) {
                return true;
            }
            try {
                Object helper = getField(billingStateListener, "this$0");
                if (helper == null) {
                    Log.e(TAG, "forceSetup: no this$0");
                    return false;
                }
                setField(helper, "bIsIapSetup", Boolean.TRUE);
                setField(helper, "mSubscriptionsSupported", Boolean.TRUE);
                setField(helper, "reconnectMilliseconds", 1000L);

                Object listener = getField(billingStateListener, "val$InSetupFinishedListener");
                if (listener != null) {
                    Method finished = listener.getClass().getMethod("onHelperSetupFinished");
                    finished.setAccessible(true);
                    finished.invoke(listener);
                    Log.d(TAG, "forceSetup: setup listener invoked");
                } else {
                    Log.e(TAG, "forceSetup: no setup listener");
                }
                forcedSetupListener = billingStateListener;
                return true;
            } catch (Throwable t) {
                // Keep the game running: a failed force falls back to the stock path.
                Log.e(TAG, "forceSetup failed", t);
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // 2 — Store catalog: answer with a local product list.
    // ------------------------------------------------------------------

    /**
     * Called from the entry of {@code MicroTransactionAndroid.refreshProductList(String[])}
     * with that method's {@code this} and the SKU array supplied by the native store.
     *
     * <p>Instead of asking Play (which fails for a re-signed build) this builds a
     * synthetic {@code Inventory} for the requested SKUs and queues the app's own
     * {@code ProductListCommand}, which is what ultimately calls
     * {@code NativeCallback_MicroTransactionOnGetAvailableProducts}.
     */
    public static boolean queueProductList(Object microTransactionAndroid, String[] rawSkus) {
        if (microTransactionAndroid == null || rawSkus == null) {
            return false;
        }
        try {
            List<String> skus = new ArrayList<>(rawSkus.length);
            for (String raw : rawSkus) {
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                skus.add(safeString(raw.toLowerCase(Locale.US)));
            }
            if (skus.isEmpty()) {
                return false;
            }

            Object inventory = buildInventory(skus);

            Class<?> storeClass = Class.forName(STORE_ANDROID);
            Class<?> commandClass = Class.forName(STORE_ANDROID + "$ProductListCommand");
            Constructor<?> commandCtor = commandClass.getDeclaredConstructor(
                    storeClass, List.class, Class.forName(INVENTORY));
            commandCtor.setAccessible(true);
            Object command = commandCtor.newInstance(microTransactionAndroid, skus, inventory);

            if (!offer(command)) {
                Log.e(TAG, "queueProductList: offer failed");
                return false;
            }
            Log.d(TAG, "queueProductList: " + skus.size() + " skus queued");
            return true;
        } catch (Throwable t) {
            // Keep the game running: fall back to the stock query/failure path.
            Log.e(TAG, "queueProductList failed", t);
            return false;
        }
    }

    private static Object commandQueue(Class<?> storeClass) throws Exception {
        try {
            Field field = storeClass.getDeclaredField("mCommandQueue");
            field.setAccessible(true);
            return field.get(null);
        } catch (NoSuchFieldException ignored) {
            Method accessor = storeClass.getDeclaredMethod("access$300");
            accessor.setAccessible(true);
            return accessor.invoke(null);
        }
    }

    private static Object buildInventory(List<String> skus) throws Exception {
        Class<?> inventoryClass = Class.forName(INVENTORY);
        Object inventory = inventoryClass.getDeclaredConstructor().newInstance();

        Class<?> detailsClass = Class.forName(PRODUCT_DETAILS);
        Constructor<?> detailsCtor = detailsClass.getDeclaredConstructor(String.class);
        detailsCtor.setAccessible(true);
        Method addDetails = inventoryClass.getMethod("addProductDetails", detailsClass);

        for (String sku : skus) {
            Object details = detailsCtor.newInstance(productDetailsJson(sku));
            addDetails.invoke(inventory, details);
        }
        return inventory;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Mirror of {@code MicroTransactionAndroid.getSafeString(String)}. */
    private static String safeString(String sku) {
        if (sku.length() <= 16) {
            return sku;
        }
        return SKU_PREFIX + sku.substring(16);
    }

    private static String productDetailsJson(String sku) {
        String title = displayName(sku);
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"productId\":").append(quote(sku)).append(',')
                .append("\"type\":\"inapp\",")
                .append("\"title\":").append(quote(title)).append(',')
                .append("\"name\":").append(quote(sku)).append(',')
                .append("\"description\":").append(quote("In-App Purchase")).append(',')
                .append("\"packageDisplayName\":").append(quote("Injustice: Gods Among Us")).append(',')
                .append("\"skuDetailsToken\":").append(quote("morphe-" + sku)).append(',')
                .append("\"oneTimePurchaseOfferDetails\":{")
                .append("\"formattedPrice\":\"$0.00\",")
                .append("\"priceAmountMicros\":0,")
                .append("\"priceCurrencyCode\":\"USD\"")
                .append('}')
                .append('}');
        return json.toString();
    }

    /** Turns e.g. {@code com.wb.injustice.brawler2013.powercredits_1} into "Power Credits 1". */
    private static String displayName(String sku) {
        String name = sku;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            name = name.substring(dot + 1);
        }
        name = name.replace("powercredits_", "Power Credits ")
                .replace("nthmetal_", "Nth Metal ")
                .replace("energy_", "Energy ")
                .replace("starterpackflash", "Starter Pack Flash ")
                .replace('_', ' ')
                .trim();
        if (name.isEmpty()) {
            return sku;
        }
        StringBuilder pretty = new StringBuilder(name.length());
        boolean upper = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ') {
                upper = true;
                pretty.append(c);
            } else if (upper) {
                pretty.append(Character.toUpperCase(c));
                upper = false;
            } else {
                pretty.append(c);
            }
        }
        return pretty.toString().replaceAll("\\s+", " ").trim();
    }

    private static Object getField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    quoted.append(c);
                    break;
            }
        }
        quoted.append('"');
        return quoted.toString();
    }
}

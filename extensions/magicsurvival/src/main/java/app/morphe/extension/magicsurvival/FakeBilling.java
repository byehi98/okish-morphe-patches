package app.morphe.extension.magicsurvival;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime helper for the Magic Survival free-store patch.
 *
 * <p>Magic Survival is Unity IL2CPP, so all purchase logic lives in libil2cpp.so and the Java side
 * is only the real Google Play Billing client (v8.3.0) driven over JNI. The patch fabricates the
 * billing answers in Java and the C# side accepts them exactly like real ones. This class keeps the
 * fiddly parts (reflection into the billing SDK, receipt JSON, persisted grant set) out of smali.
 *
 * <p>Everything is called from injected smali; obfuscated billing members are reached reflectively
 * so the extension never needs the SDK on its compile classpath.
 */
@SuppressWarnings({"unused", "DiscouragedApi"})
public final class FakeBilling {

    private static final String PREFS_NAME = "morphe_iap";
    private static final String OWNED_KEY = "owned";

    /** Prefix of every fabricated purchase token: {@code morphe-<productId>|<unique>}. */
    private static final String TOKEN_PREFIX = "morphe-";
    private static final char TOKEN_SEPARATOR = '|';
    private static final String RESTORE_SUFFIX = "restore";

    private static final String PACKAGE_NAME = "com.vkslrzm.Zombie";
    private static final String SIGNATURE = "morphe-sig";

    private static final String BILLING_PACKAGE = "com.android.billingclient.api.";

    private FakeBilling() {
    }

    /**
     * A fresh, successful {@code BillingResult} (response code 0 / OK).
     */
    public static Object ok() {
        try {
            Class<?> resultClass = Class.forName(BILLING_PACKAGE + "BillingResult");
            Object builder = resultClass.getMethod("newBuilder").invoke(null);
            builder = builder.getClass().getMethod("setResponseCode", int.class).invoke(builder, 0);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable t) {
            throw new IllegalStateException("Magic Survival fake billing: cannot build BillingResult", t);
        }
    }

    /**
     * Build a fabricated {@code Purchase} for a freshly tapped product and persist its token so the
     * restore path can replay the exact same grant.
     *
     * <p>Unity IAP de-duplicates purchases by transaction id / purchase token, so every fresh
     * purchase MUST get a unique token or the second purchase of the same SKU is silently dropped
     * (this was the "gems only work once" bug).
     */
    public static Object purchase(Context context, String productId) {
        String token = TOKEN_PREFIX + productId + TOKEN_SEPARATOR + uniqueSuffix();
        addToken(context, token);
        return buildPurchase(token);
    }

    /**
     * Rebuild the persisted grants for {@code queryPurchasesAsync}.
     *
     * <p>The stored token is replayed verbatim, so a query that happens while a purchase is still
     * un-consumed returns the same transaction id Unity IAP already recorded (no double grant).
     * Tokens stored by older patch versions are plain product ids and get a stable restore token.
     */
    public static List<Object> restorePurchases(Context context) {
        List<Object> purchases = new ArrayList<>();
        if (context == null) {
            return purchases;
        }
        for (String entry : ownedSet(preferences(context))) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            String token = entry.startsWith(TOKEN_PREFIX)
                    ? entry
                    : TOKEN_PREFIX + entry + TOKEN_SEPARATOR + RESTORE_SUFFIX;
            purchases.add(buildPurchase(token));
        }
        return purchases;
    }

    /**
     * Drop a consumed grant. Only tokens minted by this patch are touched.
     */
    public static void remove(Context context, String token) {
        if (context == null || token == null || !token.startsWith(TOKEN_PREFIX)) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        Set<String> owned = ownedSet(preferences);
        boolean changed = owned.remove(token);

        // Migrate/clean the legacy product-id entry ("gem_10") if one is still around.
        String productId = productIdFromToken(token);
        if (productId != null) {
            changed |= owned.remove(productId);
        }

        if (changed) {
            preferences.edit().putStringSet(OWNED_KEY, owned).apply();
        }
    }

    /**
     * Extract the product id from a fabricated purchase token, or {@code null} when the token was
     * not produced by this patch.
     */
    public static String productIdFromToken(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        int start = TOKEN_PREFIX.length();
        int end = token.indexOf(TOKEN_SEPARATOR, start);
        if (end < 0) {
            end = token.length();
        }
        if (end <= start) {
            return null;
        }
        return token.substring(start, end);
    }

    /**
     * Answer a {@code queryProductDetailsAsync} call with a locally built catalog.
     *
     * <p>Without network the Play Store cannot return the product catalog, Unity IAP fails store
     * initialization and the shop never opens. This rebuilds {@code ProductDetails} for exactly the
     * product ids the game asked for, so the store populates offline.
     */
    public static Object buildQueryResult(Object queryProductDetailsParams) {
        try {
            List<?> requested = (List<?>) queryProductDetailsParams.getClass()
                    .getMethod("zza")
                    .invoke(queryProductDetailsParams);

            Class<?> detailsClass = Class.forName(BILLING_PACKAGE + "ProductDetails");
            Constructor<?> detailsCtor = detailsClass.getDeclaredConstructor(String.class);
            detailsCtor.setAccessible(true);

            List<Object> productDetails = new ArrayList<>();
            if (requested != null) {
                for (Object product : requested) {
                    if (product == null) {
                        continue;
                    }
                    String productId = (String) product.getClass().getMethod("zza").invoke(product);
                    String productType = (String) product.getClass().getMethod("zzb").invoke(product);
                    if (productId == null || productId.isEmpty()) {
                        continue;
                    }
                    productDetails.add(detailsCtor.newInstance(productDetailsJson(productId, productType)));
                }
            }

            Class<?> queryResultClass = Class.forName(BILLING_PACKAGE + "QueryProductDetailsResult");
            Method create = queryResultClass.getMethod("create", List.class, List.class);
            return create.invoke(null, productDetails, new ArrayList<>());
        } catch (Throwable t) {
            throw new IllegalStateException("Magic Survival fake billing: cannot build product catalog", t);
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Set<String> ownedSet(SharedPreferences preferences) {
        Set<String> stored = preferences.getStringSet(OWNED_KEY, null);
        return stored == null ? new HashSet<String>() : new HashSet<>(stored);
    }

    private static void addToken(Context context, String token) {
        if (context == null) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        Set<String> owned = ownedSet(preferences);
        if (owned.add(token)) {
            preferences.edit().putStringSet(OWNED_KEY, owned).apply();
        }
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Object buildPurchase(String token) {
        try {
            Class<?> purchaseClass = Class.forName(BILLING_PACKAGE + "Purchase");
            Constructor<?> ctor = purchaseClass.getConstructor(String.class, String.class);
            return ctor.newInstance(receiptJson(token), SIGNATURE);
        } catch (Throwable t) {
            throw new IllegalStateException("Magic Survival fake billing: cannot build Purchase", t);
        }
    }

    private static String receiptJson(String token) {
        String productId = productIdFromToken(token);
        if (productId == null) {
            productId = token;
        }
        int separator = token.indexOf(TOKEN_SEPARATOR);
        String suffix = separator < 0 ? RESTORE_SUFFIX : token.substring(separator + 1);

        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"orderId\":").append(quote("GPA.morphe." + suffix)).append(',')
                .append("\"packageName\":").append(quote(PACKAGE_NAME)).append(',')
                .append("\"productId\":").append(quote(productId)).append(',')
                .append("\"productIds\":[").append(quote(productId)).append("],")
                .append("\"purchaseState\":0,")
                .append("\"purchaseTime\":0,")
                .append("\"purchaseToken\":").append(quote(token)).append(',')
                .append("\"quantity\":1,")
                .append("\"acknowledged\":true")
                .append('}');
        return json.toString();
    }

    private static String productDetailsJson(String productId, String productType) {
        String title;
        String price;
        long priceMicros;

        if ("remove_ad".equals(productId)) {
            title = "Remove Ads";
            price = "$2.99";
            priceMicros = 2990000L;
        } else if ("gem_10".equals(productId)) {
            title = "10 Gems";
            price = "$0.99";
            priceMicros = 990000L;
        } else if ("gem_16".equals(productId)) {
            title = "16 Gems";
            price = "$1.99";
            priceMicros = 1990000L;
        } else if ("gem_80".equals(productId)) {
            title = "80 Gems";
            price = "$4.99";
            priceMicros = 4990000L;
        } else if ("gem_160".equals(productId)) {
            title = "160 Gems";
            price = "$9.99";
            priceMicros = 9990000L;
        } else {
            title = productId;
            price = "$0.99";
            priceMicros = 990000L;
        }

        String type = (productType == null || productType.isEmpty()) ? "inapp" : productType;

        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"productId\":").append(quote(productId)).append(',')
                .append("\"type\":").append(quote(type)).append(',')
                .append("\"title\":").append(quote(title)).append(',')
                .append("\"name\":").append(quote(productId)).append(',')
                .append("\"description\":").append(quote(title)).append(',')
                .append("\"packageDisplayName\":").append(quote("Magic Survival")).append(',')
                .append("\"skuDetailsToken\":").append(quote("morphe-" + productId)).append(',')
                .append("\"oneTimePurchaseOfferDetails\":{")
                .append("\"formattedPrice\":").append(quote(price)).append(',')
                .append("\"priceAmountMicros\":").append(priceMicros).append(',')
                .append("\"priceCurrencyCode\":").append(quote("USD"))
                .append('}')
                .append('}');
        return json.toString();
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

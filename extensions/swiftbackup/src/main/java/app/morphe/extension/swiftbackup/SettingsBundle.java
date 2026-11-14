package app.morphe.extension.swiftbackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extends Swift Backup's anonymous "Export/Import settings" file with the rest of the local account:
 * the local Realtime Database nodes (labels, configs, schedules, favorites/blacklist, whole cloud_v1
 * catalog), the cache files that back them and the UI preferences that are not part of
 * {@code AppSettings}.
 *
 * <p>The extra payload lives under the "morpheLocalAccount" key, which Gson ignores on import, so
 * new exports still import into an unpatched install and old exports still import here.
 * Everything is stored as the app's own raw JSON (no re-modelling), which keeps the round trip
 * lossless.
 *
 * <p>Called from smali injected by the "Swift Backup Local Account Backup" patch. The extension owns
 * no obfuscated app types: the context (uid, cloud dir, ...) and the schedule re-arm are provided by
 * smali bridges in the patched class.
 */
@SuppressWarnings({"unchecked", "rawtypes", "unused"})
public final class SettingsBundle {
    private static final String TAG = "MorpheSettingsBundle";
    private static final String KEY = "morpheLocalAccount";
    private static final int VERSION = 1;

    /** RTDB nodes under users/&lt;uid&gt;/ that belong to the local account (cloud_v1 is handled apart). */
    private static final List<String> RTDB_NODES = Arrays.asList(
            "appSettings",
            "favoriteApps",
            "blacklistApps",
            "labelsData",
            "configs",
            "schedule_data");

    /** Prefs that are user state but not part of AppSettings. Secrets/identity/transient state stay out. */
    private static final List<String> PREF_KEYS = Arrays.asList(
            "app_sort_mode", "app_sort_ascending",
            "key_filter_app_backup", "key_filter_app_backup_age", "key_filter_app_enabled",
            "key_filter_app_install", "key_filter_app_synced", "key_filter_favorites",
            "key_filter_labels", "key_filter_labels_temp",
            "selected_labels_filter", "selected_labels_filter_temp", "key_is_showing_results",
            "last_used_apps_section", "app_list_activity_drawer_shown",
            "app_visibility_scroll_y", "app_visibility_search_query",
            "configs_list_sort_options", "folders_list_sort_options",
            "pinned_actions", "premium_plans_expanded",
            "protect_backup_help_shown", "cloud_connect_edit_warning_shown",
            "cloud_manual_delete_warning_shown", "user_acknowledge_no_batch_restore_on_q",
            "KEY_SCHEDULE_ENABLED");

    private static final String LABELS_CACHE = "app_labels/cached_data";
    private static final String CONFIGS_CACHE = "configs/cached_data";
    private static final String SCHEDULE_PREF = "schedule_data";
    private static final String SCHEDULE_ENABLED_PREF = "KEY_SCHEDULE_ENABLED";

    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    private SettingsBundle() {
    }

    // ---------------------------------------------------------------- export

    /**
     * Adds the local account payload to the settings JSON produced by the app.
     *
     * @return the decorated JSON, or the input unchanged when anything fails (export must succeed).
     */
    public static String decorate(String settingsJson, String contextJson) {
        try {
            JSONObject root = new JSONObject(settingsJson);
            JSONObject context = parseContext(contextJson);
            String uid = context.optString("uid", "");

            JSONObject extras = new JSONObject();
            extras.put("version", VERSION);
            extras.put("exportedAt", System.currentTimeMillis());
            copy(context, extras, "cloudDir");
            copy(context, extras, "cloudConst");
            copy(context, extras, "activeTag");

            JSONObject rtdb = collectRtdb(uid);
            if (rtdb.length() > 0) extras.put("rtdb", rtdb);

            JSONObject caches = collectCaches(rtdb);
            if (caches.length() > 0) extras.put("caches", caches);

            JSONObject prefs = collectPrefs();
            if (prefs.length() > 0) extras.put("prefs", prefs);

            root.put(KEY, extras);
            return root.toString();
        } catch (Throwable t) {
            Log.e(TAG, "decorate failed, exporting settings unchanged", t);
            return settingsJson;
        }
    }

    private static JSONObject collectRtdb(String uid) {
        JSONObject out = new JSONObject();
        if (uid == null || uid.isEmpty()) return out;
        for (String node : RTDB_NODES) {
            try {
                String json = nodeJson("users", uid, node);
                if (json != null) out.put(node, new JSONObject(json));
            } catch (Throwable t) {
                Log.e(TAG, "decorate: rtdb node " + node + " failed", t);
            }
        }
        try {
            // The whole cloud catalog (tags/apps/folders/walls/wifi/metadata/counters) in one go.
            String json = nodeJson("users", uid, "cloud_v1");
            if (json != null) out.put("cloud_v1", new JSONObject(json));
        } catch (Throwable t) {
            Log.e(TAG, "decorate: rtdb node cloud_v1 failed", t);
        }
        return out;
    }

    /**
     * Prefers the live database node; falls back to the cache file / schedule pref for installs whose
     * tree predates the local database patch.
     */
    private static JSONObject collectCaches(JSONObject rtdb) {
        JSONObject out = new JSONObject();
        try {
            String labels = optJson(rtdb, "labelsData");
            if (labels == null) labels = readFile(LABELS_CACHE);
            if (labels != null) out.put("labels", new JSONObject(labels));
        } catch (Throwable t) {
            Log.e(TAG, "decorate: labels cache failed", t);
        }
        try {
            String configs = optJson(rtdb, "configs");
            if (configs == null) configs = readFile(CONFIGS_CACHE);
            if (configs != null) out.put("configs", new JSONObject(configs));
        } catch (Throwable t) {
            Log.e(TAG, "decorate: configs cache failed", t);
        }
        try {
            String schedule = readPref(SCHEDULE_PREF);
            if (schedule == null) schedule = optJson(rtdb, "schedule_data");
            if (schedule != null) out.put("schedule", new JSONObject(schedule));
        } catch (Throwable t) {
            Log.e(TAG, "decorate: schedule cache failed", t);
        }
        return out;
    }

    private static JSONObject collectPrefs() {
        JSONObject out = new JSONObject();
        try {
            SharedPreferences preferences = prefs();
            if (preferences == null) return out;
            Map<String, ?> all = preferences.getAll();
            for (String key : PREF_KEYS) {
                if (!all.containsKey(key)) continue;
                Object value = all.get(key);
                if (value instanceof Set) {
                    JSONArray array = new JSONArray();
                    for (Object item : (Set<?>) value) array.put(String.valueOf(item));
                    out.put(key, array);
                } else if (value instanceof Boolean || value instanceof Number || value instanceof String) {
                    out.put(key, value);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "decorate: prefs failed", t);
        }
        return out;
    }

    // ---------------------------------------------------------------- import

    /**
     * Restores the local account payload.
     *
     * @return true when schedules were restored and the caller should re-arm the alarms.
     */
    public static boolean apply(String settingsJson, String contextJson) {
        try {
            JSONObject root = new JSONObject(settingsJson);
            JSONObject extras = root.optJSONObject(KEY);
            if (extras == null) return false;
            int version = extras.optInt("version", VERSION);
            if (version > VERSION) {
                Log.w(TAG, "import: bundle version " + version + " > " + VERSION + ", skipping extras");
                return false;
            }

            JSONObject context = parseContext(contextJson);
            String uid = context.optString("uid", "");
            if (uid == null || uid.isEmpty()) {
                Log.w(TAG, "import: no uid in context, skipping extras");
                return false;
            }

            JSONObject rtdb = extras.optJSONObject("rtdb");
            JSONObject caches = extras.optJSONObject("caches");
            String exportedDir = extras.optString("cloudDir", "");
            String currentDir = context.optString("cloudDir", "");
            if (exportedDir.isEmpty()) exportedDir = currentDir;

            try {
                applyRtdb(rtdb, uid, exportedDir, currentDir);
            } catch (Throwable t) {
                Log.e(TAG, "import: rtdb failed", t);
            }

            try {
                applyPrefs(extras.optJSONObject("prefs"));
            } catch (Throwable t) {
                Log.e(TAG, "import: prefs failed", t);
            }

            // Last, so the schedule flag written here wins over the exported pref value.
            boolean schedules = false;
            try {
                schedules = applyCaches(caches, rtdb);
            } catch (Throwable t) {
                Log.e(TAG, "import: caches failed", t);
            }

            return schedules;
        } catch (Throwable t) {
            Log.e(TAG, "apply failed", t);
            return false;
        }
    }

    private static void applyRtdb(JSONObject rtdb, String uid, String exportedDir, String currentDir)
            throws Exception {
        if (rtdb == null) return;
        Map<String, Object> nodes = new LinkedHashMap<>();
        String base = "users/" + uid + "/";

        for (String node : RTDB_NODES) {
            String json = optJson(rtdb, node);
            if (json == null) continue;
            nodes.put(base + node, LocalDb.parseJson(json));
        }

        JSONObject cloud = rtdb.optJSONObject("cloud_v1");
        if (cloud != null) {
            List<String> dirs = new ArrayList<>();
            for (Iterator<String> it = cloud.keys(); it.hasNext(); ) dirs.add(it.next());

            for (String dir : dirs) {
                JSONObject subtree = cloud.optJSONObject(dir);
                if (subtree == null) continue;
                String path = base + "cloud_v1/" + sanitize(dir);
                nodes.put(path, LocalDb.parseJson(subtree.toString()));
            }

            // Also expose the catalog under the dir this install resolves today (a fresh install has
            // no cloud email yet), so the tags are visible before the cloud account is reconnected.
            String primary = dirs.contains(exportedDir) ? exportedDir : (dirs.isEmpty() ? null : dirs.get(0));
            String target = sanitize(currentDir);
            if (primary != null && !target.isEmpty() && !dirs.contains(currentDir)) {
                JSONObject subtree = cloud.optJSONObject(primary);
                if (subtree != null) {
                    nodes.put(base + "cloud_v1/" + target, LocalDb.parseJson(subtree.toString()));
                }
            }
        }

        if (nodes.isEmpty()) return;
        LocalDb.importNodes(nodes);
        Log.i(TAG, "import: " + nodes.size() + " rtdb node(s) under users/" + uid);
    }

    private static void applyPrefs(JSONObject prefs) {
        if (prefs == null || prefs.length() == 0) return;
        SharedPreferences.Editor editor = edit();
        if (editor == null) return;
        for (Iterator<String> it = prefs.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = prefs.opt(key);
            try {
                if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Number) {
                    editor.putInt(key, ((Number) value).intValue());
                } else if (value instanceof JSONArray) {
                    Set<String> set = new LinkedHashSet<>();
                    JSONArray array = (JSONArray) value;
                    for (int i = 0; i < array.length(); i++) set.add(String.valueOf(array.opt(i)));
                    editor.putStringSet(key, set);
                } else if (value != null) {
                    editor.putString(key, String.valueOf(value));
                }
            } catch (Throwable t) {
                Log.e(TAG, "import: pref " + key + " failed", t);
            }
        }
        if (!editor.commit()) Log.w(TAG, "import: prefs commit failed");
    }

    /** @return true when the restored schedule data contains at least one schedule. */
    private static boolean applyCaches(JSONObject caches, JSONObject rtdb) {
        String labels = optJson(caches, "labels");
        if (labels == null) labels = optJson(rtdb, "labelsData");
        if (labels != null) writeCache(LABELS_CACHE, labels);

        String configs = optJson(caches, "configs");
        if (configs == null) configs = optJson(rtdb, "configs");
        if (configs != null) writeCache(CONFIGS_CACHE, configs);

        String schedule = optJson(caches, "schedule");
        if (schedule == null) schedule = optJson(rtdb, "schedule_data");
        if (schedule == null) return false;

        boolean schedules = hasSchedules(schedule);
        SharedPreferences.Editor editor = edit();
        if (editor == null) return schedules;
        editor.putString(SCHEDULE_PREF, schedule);
        if (schedules) editor.putBoolean(SCHEDULE_ENABLED_PREF, true);
        if (!editor.commit()) Log.w(TAG, "import: schedule prefs commit failed");
        return schedules;
    }

    private static boolean hasSchedules(String scheduleJson) {
        try {
            return hasSchedules(new JSONObject(scheduleJson));
        } catch (Throwable t) {
            Log.e(TAG, "unparseable schedule data", t);
            return false;
        }
    }

    private static boolean hasSchedules(JSONObject scheduleData) {
        if (scheduleData == null || scheduleData.length() == 0) return false;
        for (Iterator<String> it = scheduleData.keys(); it.hasNext(); ) {
            Object value = scheduleData.opt(it.next());
            if (value instanceof JSONArray && ((JSONArray) value).length() > 0) return true;
            if (value instanceof JSONObject && ((JSONObject) value).length() > 0) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- files / prefs

    /** Writes a cache file inside the app's files dir (tmp + fsync + rename), like the app does. */
    private static void writeCache(String relativePath, String json) {
        try {
            File dir = LocalDb.filesDir();
            if (dir == null) return;
            File out = new File(dir, relativePath);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("mkdirs failed: " + parent);
            }
            File tmp = new File(out.getPath() + ".morphe.tmp");
            try (FileOutputStream stream = new FileOutputStream(tmp)) {
                stream.write(json.getBytes(StandardCharsets.UTF_8));
                stream.flush();
                stream.getFD().sync();
            }
            if (!tmp.renameTo(out)) throw new IOException("rename failed: " + out);
            Log.i(TAG, "import: wrote " + relativePath);
        } catch (Throwable t) {
            Log.e(TAG, "import: writing " + relativePath + " failed", t);
        }
    }

    private static String readFile(String relativePath) {
        try {
            File dir = LocalDb.filesDir();
            if (dir == null) return null;
            File f = new File(dir, relativePath);
            if (!f.isFile() || f.length() <= 0 || f.length() > MAX_FILE_BYTES) return null;
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = in.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "reading " + relativePath + " failed", t);
            return null;
        }
    }

    /** The prefs file the app uses: &lt;pkg&gt;_preferences. */
    private static SharedPreferences prefs() {
        Context context = app();
        if (context == null) return null;
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    private static SharedPreferences.Editor edit() {
        SharedPreferences preferences = prefs();
        return preferences == null ? null : preferences.edit();
    }

    private static String readPref(String key) {
        SharedPreferences preferences = prefs();
        if (preferences == null) return null;
        String value = preferences.getString(key, null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static Context app() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            Log.e(TAG, "no application context", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String nodeJson(String... path) {
        try {
            Object node = LocalDb.node(path);
            if (node == null) return null;
            return LocalDb.toJsonString(node);
        } catch (Throwable t) {
            StringBuilder joined = new StringBuilder();
            for (String part : path) {
                if (joined.length() > 0) joined.append('/');
                joined.append(part);
            }
            Log.e(TAG, "node " + joined + " failed", t);
            return null;
        }
    }

    private static String optJson(JSONObject object, String key) {
        if (object == null) return null;
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject || value instanceof JSONArray) return value.toString();
        String string = String.valueOf(value);
        return string.isEmpty() ? null : string;
    }

    private static void copy(JSONObject from, JSONObject to, String key) {
        try {
            String value = from.optString(key, null);
            if (value != null && !value.isEmpty()) to.put(key, value);
        } catch (Throwable ignored) {
            // Optional metadata only.
        }
    }

    private static JSONObject parseContext(String contextJson) {
        try {
            return contextJson == null ? new JSONObject() : new JSONObject(contextJson);
        } catch (Throwable t) {
            Log.w(TAG, "invalid context, using empty one");
            return new JSONObject();
        }
    }

    /** Same filtering the app applies to the cloud dir segment (drop / . # $ [ ]). */
    private static String sanitize(String dir) {
        if (dir == null) return "";
        StringBuilder out = new StringBuilder(dir.length());
        for (int i = 0; i < dir.length(); i++) {
            char c = dir.charAt(i);
            if (c == '/' || c == '.' || c == '#' || c == '$' || c == '[' || c == ']') continue;
            out.append(c);
        }
        return out.toString();
    }
}

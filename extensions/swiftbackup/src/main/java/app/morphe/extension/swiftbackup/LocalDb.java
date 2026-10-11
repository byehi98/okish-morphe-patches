package app.morphe.extension.swiftbackup;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Local replacement for Swift Backup's Firebase Realtime Database backend.
 * Keeps the whole tree in memory and mirrors it to a JSON file in the app's files dir.
 * Called from smali injected by the "Swift Backup Local Cloud Metadata" patch.
 */
@SuppressWarnings({"unchecked", "rawtypes", "unused"})
public final class LocalDb {
    private static final String TAG = "MorpheLocalDb";
    private static final String FILE_NAME = "morphe_local_db.json";
    private static final String FALLBACK_DIR = "/data/data/org.swiftapps.swiftbackup/files";

    private static Map<String, Object> root;
    private static File file;

    private LocalDb() {
    }

    /** Returns the whole tree (plain Maps/Lists/Strings/Longs/Doubles/Booleans). */
    public static synchronized Object load() {
        if (root != null) return root;
        root = new HashMap<>();
        try {
            File f = file();
            if (f.isFile()) {
                byte[] bytes = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0;
                    while (off < bytes.length) {
                        int n = in.read(bytes, off, bytes.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                }
                Object parsed = fromJson(new JSONObject(new String(bytes, "UTF-8")));
                if (parsed instanceof Map) root = (Map<String, Object>) parsed;
            }
        } catch (Throwable t) {
            log("load failed", t);
        }
        return root;
    }

    /**
     * Applies a put ("p") or merge ("m") at the given path.
     *
     * @return the complete value at the path after the write (null if nothing is left there).
     */
    public static synchronized Object write(String action, List path, Object data) {
        load();
        List<String> segments = new ArrayList<>();
        if (path != null) for (Object o : path) segments.add(String.valueOf(o));
        try {
            if ("m".equals(action) && data instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) data).entrySet()) {
                    List<String> child = new ArrayList<>(segments);
                    for (String part : String.valueOf(e.getKey()).split("/")) {
                        if (!part.isEmpty()) child.add(part);
                    }
                    put(child, e.getValue());
                }
            } else {
                put(segments, data);
            }
            save();
        } catch (Throwable t) {
            log("write failed", t);
        }
        return get(segments);
    }

    private static void put(List<String> path, Object value) {
        value = resolve(value, get(path));
        if (path.isEmpty()) {
            root = value instanceof Map ? (Map<String, Object>) value : new HashMap<String, Object>();
            return;
        }
        root = (Map<String, Object>) set(root, path, 0, value);
        if (root == null) root = new HashMap<>();
    }

    /** Returns the node with the value applied, or null if the node became empty. */
    private static Object set(Object node, List<String> path, int index, Object value) {
        if (index == path.size()) return isEmpty(value) ? null : value;
        Map<String, Object> map = asMap(node);
        String key = path.get(index);
        Object child = set(map.get(key), path, index + 1, value);
        if (child == null) map.remove(key);
        else map.put(key, child);
        return map.isEmpty() ? null : map;
    }

    private static Object get(List<String> path) {
        Object node = root;
        for (String key : path) {
            if (node instanceof List) node = asMap(node);
            if (!(node instanceof Map)) return null;
            node = ((Map) node).get(key);
        }
        return node;
    }

    private static Map<String, Object> asMap(Object node) {
        if (node instanceof Map) return (Map<String, Object>) node;
        Map<String, Object> map = new HashMap<>();
        if (node instanceof List) {
            List list = (List) node;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != null) map.put(String.valueOf(i), list.get(i));
            }
        }
        return map;
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof Map && ((Map) value).isEmpty())
                || (value instanceof List && ((List) value).isEmpty());
    }

    /** Resolves server value placeholders and normalises number types. */
    private static Object resolve(Object value, Object existing) {
        if (value instanceof Map) {
            Map<?, ?> in = (Map<?, ?>) value;
            Object sv = in.get(".sv");
            if (sv != null) {
                if ("timestamp".equals(sv)) return System.currentTimeMillis();
                if (sv instanceof Map && ((Map) sv).get("increment") instanceof Number) {
                    Number delta = (Number) ((Map) sv).get("increment");
                    if (existing instanceof Number) {
                        Number current = (Number) existing;
                        if (current instanceof Double || delta instanceof Double) {
                            return current.doubleValue() + delta.doubleValue();
                        }
                        return current.longValue() + delta.longValue();
                    }
                    return delta instanceof Double ? (Object) delta : (Object) delta.longValue();
                }
            }
            Map existingMap = existing instanceof Map ? (Map) existing : null;
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : in.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object child = resolve(e.getValue(), existingMap == null ? null : existingMap.get(key));
                if (!isEmpty(child)) out.put(key, child);
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List) value) out.add(resolve(o, null));
            return out;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) return ((Number) value).doubleValue();
        return value;
    }

    private static void save() throws Exception {
        File f = file();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(((JSONObject) toJson(root)).toString().getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename failed: " + f);
    }

    private static Object toJson(Object value) throws Exception {
        if (value instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                o.put(String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return o;
        }
        if (value instanceof List) {
            JSONArray a = new JSONArray();
            for (Object o : (List) value) a.put(o == null ? JSONObject.NULL : toJson(o));
            return a;
        }
        return value;
    }

    private static Object fromJson(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            Map<String, Object> map = new HashMap<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next();
                Object child = fromJson(o.get(key));
                if (child != null) map.put(key, child);
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) list.add(fromJson(a.get(i)));
            return list;
        }
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Integer) return ((Integer) value).longValue();
        return value;
    }

    private static File file() throws Exception {
        if (file == null) {
            File dir;
            try {
                // No Context is reachable from the Firebase run loop, so ask the framework.
                Object app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                dir = (File) app.getClass().getMethod("getFilesDir").invoke(app);
            } catch (Throwable t) {
                dir = new File(FALLBACK_DIR);
            }
            file = new File(dir, FILE_NAME);
        }
        return file;
    }

    private static void log(String message, Throwable t) {
        Log.e(TAG, message, t);
    }

    /**
     * Wraps a PersistentConnection request callback so it can be posted to the Repo run loop.
     * The callback type is obfuscated, so it is invoked reflectively: (null, null) means success.
     */
    public static Runnable ack(final Object callback) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    for (Method m : callback.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 2 && p[0] == String.class && p[1] == String.class
                                && m.getReturnType() == void.class) {
                            m.invoke(callback, null, null);
                            return;
                        }
                    }
                    log("ack: no callback method on " + callback.getClass(), null);
                } catch (Throwable t) {
                    log("ack failed", t);
                }
            }
        };
    }

    /** No-op implementation of the (obfuscated) ValueEventListener interface. */
    public static Object noopListener(Class<?> listenerInterface) {
        return Proxy.newProxyInstance(listenerInterface.getClassLoader(),
                new Class<?>[]{listenerInterface}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        switch (method.getName()) {
                            case "equals":
                                return proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "MorpheLocalDbListener";
                            default:
                                return null;
                        }
                    }
                });
    }
}

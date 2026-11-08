package dev.jamjet.runtime.instrument;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic fingerprint of a call's arguments, recorded with each checkpoint so that a
 * replayed call with different arguments is caught.
 *
 * <p>No method of an argument is called, apart from {@code toString} on a fixed list of JDK
 * value types. Fingerprinting must not do I/O ({@code URL.getContent()}), trigger lazy
 * loading, or read the environment.</p>
 * <ul>
 *   <li>JDK value types, by a fixed allow-list: {@code String}, {@code Boolean},
 *       {@code Character}, the boxed numbers, {@code BigInteger}, {@code BigDecimal}, enums
 *       (by name), {@code UUID}, {@code URI}, {@code URL} (its text, no network),
 *       {@code java.time} values and {@code Class} (by name).</li>
 *   <li>Arrays, {@code Optional}, and JDK collections and maps, element by element; sets and
 *       maps independent of iteration order.</li>
 *   <li>Records, by their component fields.</li>
 *   <li>Your own value objects (a class with its own {@code equals} and {@code hashCode}), by
 *       their instance fields (not static, not transient), read directly; getters are never
 *       called.</li>
 * </ul>
 * <p>Anything else raises {@link CheckpointKeyException}: other JDK types (such as
 * {@code Date} or {@code StringBuilder}), collection or map implementations outside the
 * JDK, objects with identity semantics (a client, a session, a lambda), a cycle, or nesting
 * deeper than {@value #MAX_DEPTH}.</p>
 *
 * <p>Caveat: a set is encoded sorted, but a {@code List} filled from a {@code HashSet}'s
 * iteration can come out in another order in another run, and then fingerprints differ.</p>
 */
final class ArgumentFingerprint {

    static final int MAX_DEPTH = 32;

    private ArgumentFingerprint() {}

    /** Hex SHA-256 (first 128 bits) over the canonical encoding of {@code args}. */
    static String of(Method method, Object[] args) {
        StringBuilder out = new StringBuilder();
        Object[] values = args == null ? new Object[0] : args;
        out.append(values.length).append('|');
        for (int i = 0; i < values.length; i++) {
            try {
                encode(out, values[i], 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            } catch (CheckpointKeyException e) {
                throw new CheckpointKeyException("@Checkpoint " + method.getDeclaringClass().getName() + "."
                        + method.getName() + ": argument " + i + " has no stable fingerprint ("
                        + e.getMessage() + "). Pass a value type, a record, or an object with its own equals and "
                        + "hashCode; or call DurabilityContext.replayOrExecute with your own id.", e.getCause());
            }
            out.append('|');
        }
        return sha256(out.toString()).substring(0, 32);
    }

    private static void encode(StringBuilder out, Object v, int depth, Set<Object> path) {
        if (depth > MAX_DEPTH) {
            throw new CheckpointKeyException("nested deeper than " + MAX_DEPTH);
        }
        if (v == null) {
            out.append('N');
            return;
        }
        Class<?> type = v.getClass();
        if (v instanceof String s) {
            text(out, 'S', s);
        } else if (v instanceof Boolean || v instanceof Character || v instanceof Byte || v instanceof Short
                || v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof Double
                || type == BigInteger.class || type == BigDecimal.class) {
            text(out, 'P', type.getName() + '=' + v);
        } else if (v instanceof Enum<?> e) {
            text(out, 'E', e.getDeclaringClass().getName() + '.' + e.name());
        } else if (type == UUID.class || type == URI.class || type == URL.class
                || (type.getName().startsWith("java.time.") && type.getModule() == Object.class.getModule())) {
            text(out, 'V', type.getName() + '=' + v);
        } else if (v instanceof Class<?> c) {
            text(out, 'C', c.getName());
        } else if (type.isArray()) {
            enter(path, v);
            int n = Array.getLength(v);
            out.append('A').append(type.getComponentType().getName()).append('[').append(n).append(']');
            for (int i = 0; i < n; i++) {
                encode(out, Array.get(v, i), depth + 1, path);
                out.append(',');
            }
            path.remove(v);
        } else if (v instanceof Optional<?> o) {
            out.append('O');
            encode(out, o.orElse(null), depth + 1, path);
        } else if (type.isRecord()) {
            enter(path, v);
            out.append('R').append(type.getName()).append('{');
            for (RecordComponent rc : type.getRecordComponents()) {
                out.append(rc.getName()).append('=');
                encode(out, read(declaredField(type, rc.getName()), v), depth + 1, path);
                out.append(',');
            }
            out.append('}');
            path.remove(v);
        } else if (v instanceof Map<?, ?> m) {
            requireJdk(type, "map");
            enter(path, v);
            List<String> entries = new ArrayList<>(m.size());
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                StringBuilder e = new StringBuilder();
                encode(e, entry.getKey(), depth + 1, path);
                e.append("=>");
                encode(e, entry.getValue(), depth + 1, path);
                entries.add(e.toString());
            }
            Collections.sort(entries);
            out.append('M').append(entries.size()).append('{');
            entries.forEach(e -> out.append(e.length()).append(':').append(e));
            out.append('}');
            path.remove(v);
        } else if (v instanceof Set<?> s) {
            requireJdk(type, "set");
            enter(path, v);
            List<String> items = new ArrayList<>(s.size());
            for (Object item : s) {
                StringBuilder e = new StringBuilder();
                encode(e, item, depth + 1, path);
                items.add(e.toString());
            }
            Collections.sort(items);
            out.append('T').append(items.size()).append('{');
            items.forEach(e -> out.append(e.length()).append(':').append(e));
            out.append('}');
            path.remove(v);
        } else if (v instanceof Collection<?> c) {
            requireJdk(type, "collection");
            enter(path, v);
            out.append('L').append(c.size()).append('[');
            for (Object item : c) {
                encode(out, item, depth + 1, path);
                out.append(',');
            }
            out.append(']');
            path.remove(v);
        } else if (isJdk(type)) {
            throw new CheckpointKeyException(type.getName() + " is a JDK type outside the fingerprint's allow-list");
        } else {
            encodeValueObject(out, v, type, depth, path);
        }
    }

    /** A value object, by its instance fields; never by calling its methods. */
    private static void encodeValueObject(StringBuilder out, Object v, Class<?> type, int depth, Set<Object> path) {
        if (!hasValueSemantics(type)) {
            throw new CheckpointKeyException(type.getName() + " has identity semantics (no equals and hashCode of "
                    + "its own): a client, a session or a lambda cannot key a checkpoint. Pass the values it "
                    + "carries, make it a record, or give it equals and hashCode");
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> k = type; k != Object.class; k = k.getSuperclass()) {
            if (isJdk(k)) {
                throw new CheckpointKeyException(type.getName() + " extends the JDK type " + k.getName()
                        + ", whose state cannot be read safely");
            }
            for (Field f : k.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) && !Modifier.isTransient(mod) && !f.isSynthetic()) {
                    fields.add(f);
                }
            }
        }
        fields.sort(Comparator.comparing((Field f) -> f.getDeclaringClass().getName()).thenComparing(Field::getName));
        enter(path, v);
        out.append('B').append(type.getName()).append('{');
        for (Field f : fields) {
            out.append(f.getDeclaringClass().getName()).append('.').append(f.getName()).append('=');
            encode(out, read(f, v), depth + 1, path);
            out.append(',');
        }
        out.append('}');
        path.remove(v);
    }

    static boolean hasValueSemantics(Class<?> type) {
        try {
            return type.getMethod("equals", Object.class).getDeclaringClass() != Object.class
                    && type.getMethod("hashCode").getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean isJdk(Class<?> type) {
        String n = type.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    private static void requireJdk(Class<?> type, String what) {
        if (!isJdk(type)) {
            throw new CheckpointKeyException(type.getName() + " is a " + what + " implementation outside the JDK; "
                    + "pass a java.util " + what);
        }
    }

    private static Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new CheckpointKeyException("record " + type.getName() + " has no field " + name, e);
        }
    }

    private static Object read(Field f, Object owner) {
        try {
            if (!f.trySetAccessible()) {
                throw new CheckpointKeyException("cannot read " + f.getDeclaringClass().getName() + "." + f.getName()
                        + " (module not open to reflection)");
            }
            return f.get(owner);
        } catch (IllegalAccessException | RuntimeException e) {
            if (e instanceof CheckpointKeyException cke) {
                throw cke;
            }
            throw new CheckpointKeyException("cannot read " + f.getDeclaringClass().getName() + "." + f.getName()
                    + ": " + e, e);
        }
    }

    private static void enter(Set<Object> path, Object v) {
        if (!path.add(v)) {
            throw new CheckpointKeyException(v.getClass().getName() + " contains itself");
        }
    }

    private static void text(StringBuilder out, char tag, String s) {
        out.append(tag).append(s.length()).append(':').append(s);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package dev.jamjet.runtime.instrument;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic fingerprint of a call's arguments, used in checkpoint ids so that a call
 * with different arguments never replays another call's result.
 *
 * <p>Value types (strings, numbers, booleans, characters, enums, {@code java.time} values,
 * UUIDs, URIs), arrays, {@code Optional}, records, collections and maps are encoded
 * structurally. Sets and maps are encoded order-independently. Any other object must be a
 * value object (its own {@code equals} and {@code hashCode}); its readable properties are
 * encoded by the same rules, sorted by name. An argument that cannot be encoded (identity
 * semantics such as a client or a lambda, no readable properties, a cycle, nesting deeper
 * than {@value #MAX_DEPTH}) raises {@link CheckpointKeyException}.</p>
 */
final class ArgumentFingerprint {

    static final int MAX_DEPTH = 32;

    /** Used only to find a value object's readable properties, as Jackson would. */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

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
                || v instanceof BigInteger || v instanceof BigDecimal) {
            text(out, 'P', type.getName() + '=' + v);
        } else if (v instanceof Enum<?> e) {
            text(out, 'E', e.getDeclaringClass().getName() + '.' + e.name());
        } else if (v instanceof UUID || v instanceof URI || type.getName().startsWith("java.time.")) {
            text(out, 'V', type.getName() + '=' + v);
        } else if (v instanceof Class<?> c) {
            text(out, 'C', c.getName());
        } else if (v instanceof CharSequence cs) {
            text(out, 'S', cs.toString());
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
                encode(out, component(rc, v), depth + 1, path);
                out.append(',');
            }
            out.append('}');
            path.remove(v);
        } else if (v instanceof Map<?, ?> m) {
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
            enter(path, v);
            out.append('L').append(c.size()).append('[');
            for (Object item : c) {
                encode(out, item, depth + 1, path);
                out.append(',');
            }
            out.append(']');
            path.remove(v);
        } else {
            encodeBean(out, v, type, depth, path);
        }
    }

    /**
     * A value object: its readable properties (as Jackson finds them), sorted by name, each
     * encoded by these rules, so sets and maps inside it are order-independent too. A class
     * that inherits {@code Object.equals} or {@code Object.hashCode} has identity semantics
     * (a client, a session, a lambda): two calls with "the same" one cannot be told apart
     * from two calls with different ones, so it is refused.
     */
    private static void encodeBean(StringBuilder out, Object v, Class<?> type, int depth, Set<Object> path) {
        if (!hasValueSemantics(type)) {
            throw new CheckpointKeyException(type.getName() + " has identity semantics (no equals and hashCode of "
                    + "its own): a client, a session or a lambda cannot key a checkpoint. Pass the values it "
                    + "carries, make it a record, or give it equals and hashCode");
        }
        List<BeanPropertyDefinition> props;
        try {
            BeanDescription desc = JSON.getSerializationConfig().introspect(JSON.constructType(type));
            props = desc.findProperties().stream()
                    .filter(BeanPropertyDefinition::couldSerialize)
                    .filter(p -> p.getAccessor() != null)
                    .sorted(java.util.Comparator.comparing(BeanPropertyDefinition::getName))
                    .toList();
        } catch (RuntimeException e) {
            throw new CheckpointKeyException("cannot read the properties of " + type.getName() + ": " + e, e);
        }
        if (props.isEmpty()) {
            throw new CheckpointKeyException(type.getName() + " has no readable properties");
        }
        enter(path, v);
        out.append('B').append(type.getName()).append('{');
        for (BeanPropertyDefinition p : props) {
            AnnotatedMember accessor = p.getAccessor();
            Object value;
            try {
                accessor.fixAccess(true);
                value = accessor.getValue(v);
            } catch (RuntimeException e) {
                throw new CheckpointKeyException("cannot read " + type.getName() + "." + p.getName() + ": " + e, e);
            }
            out.append(p.getName()).append('=');
            encode(out, value, depth + 1, path);
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

    private static void enter(Set<Object> path, Object v) {
        if (!path.add(v)) {
            throw new CheckpointKeyException(v.getClass().getName() + " contains itself");
        }
    }

    private static Object component(RecordComponent rc, Object record) {
        Method accessor = rc.getAccessor();
        try {
            accessor.trySetAccessible();
            return accessor.invoke(record);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new CheckpointKeyException("cannot read record component " + record.getClass().getName()
                    + "." + rc.getName() + ": " + e, e);
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

package dev.jamjet.agent.audit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verifies the JamJet runtime's audit log offline, with only its public key. A port of the
 * Python SDK's {@code jamjet.runtime_audit}, checked against the same test vector.
 *
 * <p>The runtime seals every audit entry into a hash chain. In Ed25519 mode
 * ({@code JAMJET_AUDIT_SIGNING_MODE=ed25519}) it also signs each entry with a private key
 * only the runtime holds, so anyone with the public key can check an export of
 * {@code GET /audit} without being able to forge one. HMAC entries are not verified here:
 * in HMAC mode a verifier could also forge.</p>
 *
 * <p>Each entry's {@code entry_hash} must be the SHA-256 the runtime computes over its fields
 * ({@code runtime/audit/src/entry.rs}, {@code AuditLogEntry::content_hash}), and its
 * {@code signature} must be a valid Ed25519 signature, by the given key, over the statement
 * {@code JCS({"alg":"Ed25519","entry_hash":..,"kid":..,"spec":"jamjet.audit.entry/v1"})}.
 * {@code kid} is the first 16 hex digits of SHA-256 over the 32-byte public key; the stored
 * signature is {@code ed25519:<kid>:<128 hex digits>}.</p>
 *
 * <pre>{@code
 * byte[] key = RuntimeAuditVerifier.publicKeyFromDidDocument(client.didDocumentText());
 * var entries = RuntimeAuditVerifier.loadEntries(client.auditLogText(200, 0));
 * RuntimeAuditVerifier.verifyEntries(RuntimeAuditVerifier.chainOrder(entries), key);
 * }</pre>
 *
 * <p>Pin the public key you trust out of band rather than fetching it from the runtime you
 * are checking: the DID document only says which key the runtime claims to use.</p>
 *
 * <p>Signatures are checked strictly, as the runtime's {@code verify_strict} does: a key or
 * an {@code R} of small order or with a non-canonical encoding, and an {@code S} not below
 * the group order, are refused before the JDK's Ed25519 check runs.</p>
 *
 * <p><strong>What a verified entry proves, and what it does not.</strong></p>
 * <ul>
 *   <li>The hash joins its text fields with 0x1f and no lengths, so a 0x1f inside a field
 *       could move a boundary without changing the hash. Every hashed text field holding
 *       U+001F is refused, as is an {@code actor_type} other than {@code human},
 *       {@code agent} or {@code system} and an integer not in canonical form.</li>
 *   <li>Only the hashed fields are covered: {@code id}, {@code event_id},
 *       {@code execution_id}, {@code sequence}, {@code event_type}, {@code actor_id},
 *       {@code actor_type}, {@code tool_call_hash}, {@code policy_decision},
 *       {@code http_request_id}, {@code http_method}, {@code http_path},
 *       {@code ip_address}, {@code created_at}, {@code raw_event}, {@code tenant_id},
 *       {@code expires_at}, {@code redacted} and {@code prev_hash}. Any other member of an
 *       entry rides along unverified: read only those fields from a verified entry.</li>
 *   <li>Some spellings hash the same and are accepted: a timestamp ending {@code Z} or
 *       {@code +00:00}; a missing {@code tenant_id} or {@code "default"}; an optional text
 *       field missing, {@code null} or {@code ""}.</li>
 *   <li>A list verifies on its own terms only. Entries cut from its start or end, or an
 *       empty list, still verify. To detect truncation, compare the first entry's
 *       {@code prev_hash} and the last entry's {@code entry_hash} (or a count) with values
 *       you recorded out of band.</li>
 * </ul>
 */
public final class RuntimeAuditVerifier {

    public static final String SIG_SPEC = "jamjet.audit.entry/v1";
    public static final String SIG_PREFIX = "ed25519:";

    private static final byte FIELD_SEP = 0x1f;
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.TWO.pow(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger I64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger I64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    /** Points of order dividing 8, sign bit cleared (libsodium's blocklist). */
    private static final Set<String> SMALL_ORDER = Set.of(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f");
    private static final Pattern HEX128 = Pattern.compile("[0-9a-f]{128}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    /** DER SubjectPublicKeyInfo header for a raw 32-byte Ed25519 key (RFC 8410). */
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    /** {@code ActorType} in {@code runtime/audit/src/entry.rs}, as {@code as_str} writes it. */
    private static final Set<String> ACTOR_TYPES = Set.of("human", "agent", "system");
    private static final List<String> OPTIONAL_TEXT = List.of(
            "tool_call_hash", "policy_decision", "http_request_id", "http_method", "http_path", "ip_address");
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private RuntimeAuditVerifier() {}

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Parses a {@code GET /audit} response ({@code {"items": [...]}}) or a JSON list of
     * entries. Every number is kept as the {@link JsonNumberText} it was written as. A
     * duplicate object key is refused: the runtime never writes one.
     *
     * @throws IllegalArgumentException when the text is not JSON of that shape
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> loadEntries(String text) {
        Object data = parse(text);
        if (data instanceof Map<?, ?> map) {
            data = map.get("items");
        }
        if (!(data instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "expected a list of audit entries or an object with an 'items' list");
        }
        List<Map<String, Object>> entries = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("an audit entry is not a JSON object");
            }
            entries.add((Map<String, Object>) item);
        }
        return entries;
    }

    /** JSON text to maps, lists, strings, booleans, {@code null} and {@link JsonNumberText}. */
    static Object parse(String text) {
        try (JsonParser p = JSON.createParser(text)) {
            JsonToken first = p.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("empty JSON text");
            }
            Object value = read(p, first);
            if (p.nextToken() != null) {
                throw new IllegalArgumentException("trailing content after the JSON value");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
    }

    private static Object read(JsonParser p, JsonToken t) throws IOException {
        switch (t) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (JsonToken k = p.nextToken(); k != JsonToken.END_OBJECT; k = p.nextToken()) {
                    String name = p.currentName();
                    map.put(name, read(p, p.nextToken()));
                }
                return map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (JsonToken v = p.nextToken(); v != JsonToken.END_ARRAY; v = p.nextToken()) {
                    list.add(read(p, v));
                }
                return list;
            }
            case VALUE_STRING -> {
                return p.getText();
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                return new JsonNumberText(p.getText());
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return null;
            }
            default -> throw new IllegalArgumentException("unexpected JSON token " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    /**
     * The runtime's {@code AuditLogEntry::content_hash} of a serialized entry.
     *
     * @throws IllegalArgumentException when a field is one the runtime would not read
     */
    public static String entryContentHash(Map<String, Object> entry, String prevHash) {
        MessageDigest h = sha256();
        field(h, uuid(entry, "id"));
        field(h, uuid(entry, "event_id"));
        field(h, utf8(hashed("execution_id", text(entry, "execution_id", false))));
        field(h, ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(i64(entry, "sequence")).array());
        field(h, utf8(hashed("event_type", text(entry, "event_type", false))));
        field(h, utf8(hashed("actor_id", text(entry, "actor_id", false))));
        String actorType = text(entry, "actor_type", false);
        if (!ACTOR_TYPES.contains(actorType)) {
            throw new IllegalArgumentException("actor_type is not one the runtime writes (human, agent, system)");
        }
        field(h, utf8(actorType));
        for (String key : OPTIONAL_TEXT) {
            field(h, utf8(hashed(key, text(entry, key, true))));
        }
        field(h, utf8(rfc3339(hashed("created_at", text(entry, "created_at", false)))));
        if (!entry.containsKey("raw_event")) {
            throw new IllegalArgumentException("raw_event is missing");
        }
        field(h, utf8(serdeJson(entry.get("raw_event"))));
        // Rust defaults a missing tenant_id to "default"; a null is not a string.
        field(h, utf8(entry.containsKey("tenant_id") ? hashed("tenant_id", text(entry, "tenant_id", false)) : "default"));
        String expires = hashed("expires_at", text(entry, "expires_at", true));
        field(h, utf8(expires.isEmpty() ? "" : rfc3339(expires)));
        Object redacted = entry.containsKey("redacted") ? entry.get("redacted") : Boolean.FALSE;
        if (!(redacted instanceof Boolean flag)) {
            throw new IllegalArgumentException("redacted is not a boolean");
        }
        field(h, new byte[]{(byte) (flag ? 1 : 0)});
        field(h, utf8(prevHash == null ? "" : hashed("prev_hash", prevHash)));
        return HexFormat.of().formatHex(h.digest());
    }

    /** The {@code kid} the runtime writes for {@code publicKey} (32 raw bytes). */
    public static String keyId(byte[] publicKey) {
        return HexFormat.of().formatHex(sha256().digest(publicKey)).substring(0, 16);
    }

    /** The bytes an entry's Ed25519 signature covers: the JCS (RFC 8785) statement. */
    public static byte[] signingStatement(String entryHash, String kid) {
        return ("{\"alg\":\"Ed25519\",\"entry_hash\":" + jcsAscii(entryHash) + ",\"kid\":" + jcsAscii(kid)
                + ",\"spec\":\"" + SIG_SPEC + "\"}").getBytes(StandardCharsets.US_ASCII);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /** {@link #verifyEntries(List, byte[], boolean)} with the chain links checked. */
    public static void verifyEntries(List<? extends Map<String, Object>> entries, byte[] publicKey)
            throws AuditChainException {
        verifyEntries(entries, publicKey, true);
    }

    /**
     * As {@link #verifyEntries(List, byte[], boolean)}, accepting a signature by any of
     * {@code trustedKeys} (for a log that spans a key rotation). Each entry's key is chosen by
     * the {@code kid} in its signature; a {@code kid} matching none of them is
     * {@code WRONG_KEY}.
     *
     * @throws IllegalArgumentException when {@code trustedKeys} is empty or holds an unusable key
     */
    public static void verifyEntries(List<? extends Map<String, Object>> entries,
                                     java.util.Collection<byte[]> trustedKeys, boolean linked)
            throws AuditChainException {
        if (trustedKeys == null || trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("no trusted keys");
        }
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (byte[] k : trustedKeys) {
            checkPublicKey(k);
            keys.put(keyId(k), jdkKey(k));
        }
        verify(entries, keys, linked);
    }

    /**
     * Verifies every entry's hash and Ed25519 signature, and throws at the first failure.
     *
     * @param linked also check that each entry's {@code prev_hash} is the previous entry's
     *               {@code entry_hash}; pass the entries oldest first (see {@link #chainOrder}).
     *               The chain spans every tenant, so one tenant's slice of a shared log is not
     *               contiguous: verify it with {@code linked = false}.
     * @throws IllegalArgumentException when {@code publicKey} is not a usable Ed25519 key
     */
    public static void verifyEntries(List<? extends Map<String, Object>> entries, byte[] publicKey,
                                     boolean linked) throws AuditChainException {
        checkPublicKey(publicKey);
        verify(entries, Map.of(keyId(publicKey), jdkKey(publicKey)), linked);
    }

    private static void verify(List<? extends Map<String, Object>> entries, Map<String, PublicKey> keys,
                               boolean linked) throws AuditChainException {
        Object prev = null;
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = entries.get(index);
            Object entryHash = entry.get("entry_hash");
            if (falsy(entryHash)) {
                throw new AuditChainException(index, AuditChainException.Reason.UNSEALED, "");
            }
            Object signature = entry.get("signature");
            if (falsy(signature)) {
                throw new AuditChainException(index, AuditChainException.Reason.UNSIGNED, "");
            }
            for (String k : List.of("entry_hash", "signature", "prev_hash")) {
                Object v = entry.get(k);
                if (v != null && !(v instanceof String)) {
                    throw new AuditChainException(index, AuditChainException.Reason.MALFORMED, k + " is not a string");
                }
            }
            if (linked && index > 0 && !Objects.equals(entry.get("prev_hash"), prev)) {
                throw new AuditChainException(index, AuditChainException.Reason.BROKEN_LINK, "");
            }
            String recomputed;
            try {
                recomputed = entryContentHash(entry, (String) entry.get("prev_hash"));
            } catch (IllegalArgumentException e) {
                throw new AuditChainException(index, AuditChainException.Reason.MALFORMED, e.getMessage());
            }
            if (!recomputed.equals(entryHash)) {
                throw new AuditChainException(index, AuditChainException.Reason.HASH_MISMATCH, "");
            }
            AuditChainException.Reason reason = checkSignature(keys, (String) entryHash, (String) signature);
            if (reason != null) {
                throw new AuditChainException(index, reason, "");
            }
            prev = entryHash;
        }
    }

    /**
     * Orders entries oldest first by following their {@code prev_hash} links.
     *
     * @throws AuditChainException ({@code BROKEN_LINK}) unless the entries form one unbroken
     *                             run: one entry whose predecessor is not in the set, and every
     *                             other entry's predecessor exactly once
     */
    public static List<Map<String, Object>> chainOrder(Collection<? extends Map<String, Object>> entries)
            throws AuditChainException {
        List<Map<String, Object>> items = new ArrayList<>(entries);
        Map<Object, Map<String, Object>> byPrev = new HashMap<>();
        Set<Object> hashes = new HashSet<>();
        for (Map<String, Object> e : items) {
            hashes.add(e.get("entry_hash"));
        }
        for (int i = 0; i < items.size(); i++) {
            Object p = items.get(i).get("prev_hash");
            if (byPrev.containsKey(p)) {
                throw new AuditChainException(i, AuditChainException.Reason.BROKEN_LINK,
                        "two entries name the same predecessor");
            }
            byPrev.put(p, items.get(i));
        }
        List<Map<String, Object>> starts = items.stream()
                .filter(e -> !hashes.contains(e.get("prev_hash")))
                .toList();
        if (starts.size() != 1) {
            throw new AuditChainException(0, AuditChainException.Reason.BROKEN_LINK,
                    starts.size() + " chain starts in the set");
        }
        List<Map<String, Object>> ordered = new ArrayList<>(items.size());
        ordered.add(starts.get(0));
        while (ordered.size() < items.size()) {
            Map<String, Object> next = byPrev.get(ordered.get(ordered.size() - 1).get("entry_hash"));
            if (next == null) {
                throw new AuditChainException(ordered.size(), AuditChainException.Reason.BROKEN_LINK, "");
            }
            ordered.add(next);
        }
        return ordered;
    }

    /**
     * The audit signing key a runtime publishes in {@code /.well-known/did.json}: its
     * {@code #audit-key} verification method, a JWK with {@code crv: Ed25519}.
     *
     * @throws IllegalArgumentException when there is no usable audit key
     */
    public static byte[] publicKeyFromDidDocument(String didDocumentJson) {
        JsonNode doc;
        try {
            doc = new ObjectMapper().readTree(didDocumentJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("the DID document is not JSON", e);
        }
        for (JsonNode vm : doc.path("verificationMethod")) {
            if (!vm.path("id").asText("").endsWith("#audit-key")) {
                continue;
            }
            JsonNode jwk = vm.path("publicKeyJwk");
            if (!"OKP".equals(jwk.path("kty").asText(null)) || !"Ed25519".equals(jwk.path("crv").asText(null))
                    || !jwk.path("x").isTextual()) {
                throw new IllegalArgumentException("the audit key is not an Ed25519 JWK");
            }
            byte[] raw = java.util.Base64.getUrlDecoder().decode(jwk.path("x").asText());
            checkPublicKey(raw);
            return raw;
        }
        throw new IllegalArgumentException(
                "the DID document publishes no audit key (is the runtime in ed25519 mode?)");
    }

    // -------------------------------------------------------------------------
    // Signature
    // -------------------------------------------------------------------------

    private static AuditChainException.Reason checkSignature(Map<String, PublicKey> keys, String entryHash,
                                                             String signature) {
        if (!signature.startsWith(SIG_PREFIX)) {
            return AuditChainException.Reason.BAD_SIGNATURE;
        }
        String[] parts = signature.substring(SIG_PREFIX.length()).split(":", -1);
        if (parts.length != 2) {
            return AuditChainException.Reason.BAD_SIGNATURE;
        }
        String kid = parts[0];
        PublicKey key = keys.get(kid);
        if (key == null) {
            return AuditChainException.Reason.WRONG_KEY;
        }
        if (!HEX128.matcher(parts[1]).matches()) {
            return AuditChainException.Reason.BAD_SIGNATURE;
        }
        byte[] raw = HexFormat.of().parseHex(parts[1]);
        if (weakPoint(Arrays.copyOfRange(raw, 0, 32)) || !canonicalScalar(Arrays.copyOfRange(raw, 32, 64))) {
            return AuditChainException.Reason.BAD_SIGNATURE;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signingStatement(entryHash, kid));
            return verifier.verify(raw) ? null : AuditChainException.Reason.BAD_SIGNATURE;
        } catch (GeneralSecurityException e) {
            return AuditChainException.Reason.BAD_SIGNATURE;
        }
    }

    /** A small-order point, or an encoding whose y is not reduced mod p. */
    static boolean weakPoint(byte[] point) {
        byte[] cleared = point.clone();
        cleared[31] &= 0x7f;
        if (SMALL_ORDER.contains(HexFormat.of().formatHex(cleared))) {
            return true;
        }
        return littleEndian(cleared).compareTo(P) >= 0;
    }

    /** {@code S < L}: anything else makes the signature malleable. */
    static boolean canonicalScalar(byte[] s) {
        return littleEndian(s).compareTo(L) < 0;
    }

    private static void checkPublicKey(byte[] publicKey) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("an Ed25519 public key is 32 bytes");
        }
        if (weakPoint(publicKey)) {
            throw new IllegalArgumentException("not a usable Ed25519 public key (small order or non-canonical)");
        }
    }

    private static PublicKey jdkKey(byte[] raw) {
        byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a usable Ed25519 public key", e);
        }
    }

    private static BigInteger littleEndian(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            be[i] = b[b.length - 1 - i];
        }
        return new BigInteger(1, be);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private static void field(MessageDigest h, byte[] b) {
        h.update(b);
        h.update(FIELD_SEP);
    }

    private static byte[] uuid(Map<String, Object> entry, String key) {
        if (!entry.containsKey(key)) {
            throw new IllegalArgumentException(key + " is missing");
        }
        if (!(entry.get(key) instanceof String s) || !UUID_TEXT.matcher(s).matches()) {
            throw new IllegalArgumentException(key + " is not a lowercase hyphenated UUID");
        }
        return HexFormat.of().parseHex(s.replace("-", ""));
    }

    private static long i64(Map<String, Object> entry, String key) {
        if (!entry.containsKey(key)) {
            throw new IllegalArgumentException(key + " is missing");
        }
        Object v = entry.get(key);
        BigInteger n;
        if (v instanceof JsonNumberText num && INTEGER.matcher(num.text()).matches()) {
            n = new BigInteger(num.text());
            if (!n.toString().equals(num.text())) {
                // "-0" or leading zeros: Rust writes neither
                throw new IllegalArgumentException(key + " is not a canonical integer");
            }
        } else if (v instanceof Integer || v instanceof Long) {
            n = BigInteger.valueOf(((Number) v).longValue());
        } else if (v instanceof BigInteger big) {
            n = big;
        } else {
            throw new IllegalArgumentException(key + " is not a JSON integer");
        }
        // Rust reads these as i64; a value outside it is not an entry Rust wrote.
        if (n.compareTo(I64_MIN) < 0 || n.compareTo(I64_MAX) > 0) {
            throw new IllegalArgumentException(key + " is outside the i64 range");
        }
        return n.longValue();
    }

    /** A string field; an absent (or, when optional, null) optional one is "". */
    private static String text(Map<String, Object> entry, String key, boolean optional) {
        if (!entry.containsKey(key) || (optional && entry.get(key) == null)) {
            if (optional) {
                return "";
            }
            throw new IllegalArgumentException(key + " is missing");
        }
        if (!(entry.get(key) instanceof String s)) {
            throw new IllegalArgumentException(key + " is not a string");
        }
        return s;
    }

    /** A text field that goes into the hash: it may not hold the 0x1f field separator. */
    private static String hashed(String key, String value) {
        if (value.indexOf('\u001f') >= 0) {
            throw new IllegalArgumentException(key + " holds U+001F, the hash's field separator");
        }
        return value;
    }

    /** The hash covers chrono's {@code to_rfc3339()}, which writes UTC as {@code +00:00}. */
    private static String rfc3339(String ts) {
        return ts.endsWith("Z") ? ts.substring(0, ts.length() - 1) + "+00:00" : ts;
    }

    private static boolean falsy(Object v) {
        return v == null
                || Boolean.FALSE.equals(v)
                || (v instanceof String s && s.isEmpty())
                || (v instanceof Collection<?> c && c.isEmpty())
                || (v instanceof Map<?, ?> m && m.isEmpty());
    }

    // -------------------------------------------------------------------------
    // serde_json text
    // -------------------------------------------------------------------------

    /**
     * {@code serde_json::to_string} of a {@code serde_json::Value}: compact, object members
     * sorted by name (code point order, as a Rust {@code BTreeMap<String, _>} sorts), numbers
     * as written.
     *
     * @throws IllegalArgumentException for a value the runtime could not have written, such
     *                                  as a {@code double} whose spelling was lost
     */
    static String serdeJson(Object value) {
        StringBuilder out = new StringBuilder();
        writeSerde(out, value);
        return out.toString();
    }

    private static void writeSerde(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof JsonNumberText n) {
            out.append(n.text());
        } else if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof String s) {
            out.append(escape(s));
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) {
                if (!(k instanceof String key)) {
                    throw new IllegalArgumentException("a JSON object key is not a string");
                }
                keys.add(key);
            }
            keys.sort(RuntimeAuditVerifier::compareCodePoints);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(escape(keys.get(i))).append(':');
                writeSerde(out, map.get(keys.get(i)));
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeSerde(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Number) {
            throw new IllegalArgumentException(
                    "numbers must be loaded with loadEntries so their spelling is kept");
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    /** serde_json's escaping: quote, backslash and control characters only. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** RFC 8785 for a string: here every member is ASCII, so this only quotes and escapes. */
    private static String jcsAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7e) {
                throw new IllegalArgumentException("statement members are ASCII");
            }
        }
        return escape(s);
    }

    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** UTF-8, refusing a lone surrogate (Rust strings cannot hold one). */
    private static byte[] utf8(String s) {
        try {
            ByteBuffer buf = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(s));
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("a string holds a lone surrogate");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

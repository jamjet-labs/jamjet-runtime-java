package dev.jamjet.agent.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The Java port against the vector the Rust test
 * {@code runtime/audit/tests/ed25519_signing.rs::the_cross_language_vector_is_current} writes
 * (copied from the jamjet repo's {@code sdk/python/tests/fixtures/runtime_audit_ed25519.json}),
 * with the Python verifier's cases ({@code sdk/python/tests/test_runtime_audit.py}).
 */
class RuntimeAuditVerifierTest {

    private static final HexFormat HEX = HexFormat.of();
    /** RFC 8032 test 1 seed: the vector is signed with it. */
    private static final byte[] SEED = HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final BigInteger L = BigInteger.TWO.pow(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final byte[] IDENTITY = identity();

    private static byte[] identity() {
        byte[] id = new byte[32];
        id[0] = 1;
        return id;
    }

    // -- vector -------------------------------------------------------------------

    private record Vector(List<Map<String, Object>> entries, byte[] publicKey, String kid) {}

    @SuppressWarnings("unchecked")
    private static Vector vector() {
        Map<String, Object> doc = (Map<String, Object>) RuntimeAuditVerifier.parse(vectorText());
        Map<String, Object> audit = (Map<String, Object>) doc.get("audit");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object e : (List<Object>) audit.get("items")) {
            entries.add(deepCopy((Map<String, Object>) e));
        }
        return new Vector(entries, HEX.parseHex((String) doc.get("public_key_hex")), (String) doc.get("kid"));
    }

    private static String vectorText() {
        try (InputStream in = RuntimeAuditVerifierTest.class.getResourceAsStream("/audit/runtime_audit_ed25519.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> mm ? deepCopy((Map<String, Object>) mm)
                : v instanceof List<?> l ? new ArrayList<>(l) : v));
        return out;
    }

    private static AuditChainException failure(List<Map<String, Object>> entries, byte[] key) {
        return catchThrowableOfType(() -> RuntimeAuditVerifier.verifyEntries(entries, key),
                AuditChainException.class);
    }

    private static byte[] freshPublicKey() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = kp.getPublic().getEncoded();
        return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }

    // -- the vector verifies ------------------------------------------------------

    @Test
    void theRuntimeChainVerifiesWithOnlyThePublicKey() throws Exception {
        Vector v = vector();
        assertThat(RuntimeAuditVerifier.keyId(v.publicKey())).isEqualTo(v.kid());
        RuntimeAuditVerifier.verifyEntries(v.entries(), v.publicKey());
        RuntimeAuditVerifier.verifyEntries(v.entries(), v.publicKey(), false);
    }

    @Test
    void loadEntriesReadsTheAuditResponseShape() throws Exception {
        String text = vectorText();
        int start = text.indexOf("\"audit\": ") + "\"audit\": ".length();
        int end = text.lastIndexOf(",\n  \"kid\"");
        List<Map<String, Object>> entries = RuntimeAuditVerifier.loadEntries(text.substring(start, end));
        RuntimeAuditVerifier.verifyEntries(entries, vector().publicKey());
    }

    // -- tampering ----------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aTamperedEventFails() {
        Vector v = vector();
        ((Map<String, Object>) v.entries().get(1).get("raw_event")).put("node_id", "n2");
        AuditChainException e = failure(v.entries(), v.publicKey());
        assertThat(e.index()).isEqualTo(1);
        assertThat(e.reason()).isEqualTo(AuditChainException.Reason.HASH_MISMATCH);
    }

    @Test
    void aTamperedTimestampFails() {
        Vector v = vector();
        Map<String, Object> first = v.entries().get(0);
        first.put("created_at", ((String) first.get("created_at")).replace(".123456789", ".123456788"));
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.HASH_MISMATCH);
    }

    @Test
    void aTamperedSignatureFails() {
        Vector v = vector();
        String sig = (String) v.entries().get(2).get("signature");
        v.entries().get(2).put("signature", sig.substring(0, sig.length() - 2) + (sig.endsWith("00") ? "11" : "00"));
        AuditChainException e = failure(v.entries(), v.publicKey());
        assertThat(e.index()).isEqualTo(2);
        assertThat(e.reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    @Test
    void aWrongKeyFails() throws Exception {
        Vector v = vector();
        assertThat(failure(v.entries(), freshPublicKey()).reason()).isEqualTo(AuditChainException.Reason.WRONG_KEY);
    }

    @Test
    void aSignatureRelabelledWithTheRightKidStillFails() throws Exception {
        Vector v = vector();
        byte[] other = freshPublicKey();
        for (Map<String, Object> e : v.entries()) {
            String sig = (String) e.get("signature");
            e.put("signature", "ed25519:" + RuntimeAuditVerifier.keyId(other) + ":" + sig.substring(sig.lastIndexOf(':') + 1));
        }
        assertThat(failure(v.entries(), other).reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    @Test
    void anHmacTagIsNotAccepted() {
        Vector v = vector();
        v.entries().get(0).put("signature", "ab".repeat(32));
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    @Test
    void anUnsignedOrUnsealedEntryFails() {
        Vector v = vector();
        v.entries().get(0).remove("signature");
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.UNSIGNED);

        Vector w = vector();
        w.entries().get(0).put("entry_hash", "");
        assertThat(failure(w.entries(), w.publicKey()).reason()).isEqualTo(AuditChainException.Reason.UNSEALED);
    }

    @Test
    void aDroppedEntryBreaksTheLink() throws Exception {
        Vector v = vector();
        List<Map<String, Object>> gap = List.of(v.entries().get(0), v.entries().get(2));
        AuditChainException e = failure(gap, v.publicKey());
        assertThat(e.index()).isEqualTo(1);
        assertThat(e.reason()).isEqualTo(AuditChainException.Reason.BROKEN_LINK);
        // each entry still stands on its own
        RuntimeAuditVerifier.verifyEntries(gap, v.publicKey(), false);
    }

    @Test
    void chainOrderRestoresOrderAndRefusesAGap() throws Exception {
        Vector v = vector();
        List<Map<String, Object>> shuffled = List.of(v.entries().get(2), v.entries().get(0), v.entries().get(1));
        RuntimeAuditVerifier.verifyEntries(RuntimeAuditVerifier.chainOrder(shuffled), v.publicKey());
        assertThatThrownBy(() -> RuntimeAuditVerifier.chainOrder(List.of(v.entries().get(0), v.entries().get(2))))
                .isInstanceOf(AuditChainException.class);
    }

    @Test
    void numbersParsedAsDoublesAreRefusedNotMisread() throws Exception {
        Vector v = vector();
        String text = vectorText();
        Map<String, Object> doc = new ObjectMapper().readValue(text, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reparsed = (List<Map<String, Object>>) ((Map<String, Object>) doc.get("audit")).get("items");
        assertThat(failure(reparsed, v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.MALFORMED);
    }

    @Test
    void aDuplicateKeyIsRefusedOnLoad() {
        assertThatThrownBy(() -> RuntimeAuditVerifier.loadEntries("[{\"id\":\"a\",\"id\":\"b\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- the DID document -----------------------------------------------------------

    @Test
    void theDidDocumentKeyIsRead() {
        Vector v = vector();
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(v.publicKey());
        String doc = "{\"id\":\"did:web:localhost%3A7700\",\"verificationMethod\":[{\"id\":\"did:web:localhost%3A7700#audit-key\","
                + "\"type\":\"JsonWebKey2020\",\"publicKeyJwk\":{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + x
                + "\",\"kid\":\"" + v.kid() + "\"}}]}";
        assertThat(RuntimeAuditVerifier.publicKeyFromDidDocument(doc)).isEqualTo(v.publicKey());
        assertThatThrownBy(() -> RuntimeAuditVerifier.publicKeyFromDidDocument("{\"id\":\"did:web:x\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- strict signature checks ----------------------------------------------------

    @Test
    void aValidSignatureWithoutItsPrefixIsRefused() {
        Vector v = vector();
        String sig = (String) v.entries().get(0).get("signature");
        v.entries().get(0).put("signature", sig.substring("ed25519:".length()));
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    /**
     * S = k*a with R the identity: valid under the plain Ed25519 equation, made with the
     * vector's private key. Same construction as the Python and Rust tests.
     */
    static String smallOrderRSignature(String entryHash, String kid, byte[] publicKey) throws Exception {
        byte[] h = Arrays.copyOf(MessageDigest.getInstance("SHA-512").digest(SEED), 32);
        h[0] &= (byte) 248;
        h[31] &= 127;
        h[31] |= 64;
        BigInteger a = le(h);
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update(IDENTITY);
        sha.update(publicKey);
        sha.update(RuntimeAuditVerifier.signingStatement(entryHash, kid));
        BigInteger k = le(sha.digest()).mod(L);
        BigInteger s = k.multiply(a).mod(L);
        return "ed25519:" + kid + ":" + HEX.formatHex(IDENTITY) + HEX.formatHex(toLe32(s));
    }

    @Test
    void theSmallOrderConstructionMatchesTheRustVector() throws Exception {
        Vector v = vector();
        String sig = smallOrderRSignature("aa".repeat(32), v.kid(), v.publicKey());
        assertThat(sig).endsWith("0100000000000000000000000000000000000000000000000000000000000000"
                + "38e5e01276466c089c0f2f58e5d5953165d544e2984f7d026cbd3cf0704d9102");
    }

    @Test
    void aSmallOrderRIsRefused() throws Exception {
        Vector v = vector();
        Map<String, Object> first = v.entries().get(0);
        String sig = smallOrderRSignature((String) first.get("entry_hash"), v.kid(), v.publicKey());
        first.put("signature", sig);
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    @Test
    void aNonCanonicalSIsRefused() throws Exception {
        Vector v = vector();
        Map<String, Object> first = v.entries().get(0);
        String sig = (String) first.get("signature");
        String hex = sig.substring(sig.lastIndexOf(':') + 1);
        BigInteger s = le(HEX.parseHex(hex.substring(64)));
        String malleable = sig.substring(0, sig.lastIndexOf(':') + 1) + hex.substring(0, 64)
                + HEX.formatHex(toLe32(s.add(L)));
        first.put("signature", malleable);
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    @Test
    void aSmallOrderPublicKeyIsRefused() {
        Vector v = vector();
        String forged = "ed25519:" + RuntimeAuditVerifier.keyId(IDENTITY) + ":" + HEX.formatHex(IDENTITY) + "00".repeat(32);
        v.entries().forEach(e -> e.put("signature", forged));
        assertThatThrownBy(() -> RuntimeAuditVerifier.verifyEntries(v.entries(), IDENTITY))
                .isInstanceOf(IllegalArgumentException.class);
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(IDENTITY);
        String doc = "{\"verificationMethod\":[{\"id\":\"d#audit-key\",\"publicKeyJwk\":"
                + "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + x + "\"}}]}";
        assertThatThrownBy(() -> RuntimeAuditVerifier.publicKeyFromDidDocument(doc))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- malformed fields -------------------------------------------------------------

    static Stream<Arguments> retypedFields() {
        return Stream.of(
                Arguments.of("sequence", "1"),
                Arguments.of("sequence", true),
                Arguments.of("redacted", new JsonNumberText("0")),
                Arguments.of("redacted", List.of()),
                Arguments.of("tenant_id", null),
                Arguments.of("id", "urn:uuid:A1A1A1A1-A1A1-A1A1-A1A1-A1A1A1A1A1A1"),
                Arguments.of("id", "A1A1A1A1-A1A1-A1A1-A1A1-A1A1A1A1A1A1"),
                Arguments.of("event_id", "{01010101010101010101010101010101}"),
                Arguments.of("sequence", new JsonNumberText("99999999999999999999")),
                Arguments.of("sequence", new JsonNumberText("-9223372036854775809")),
                Arguments.of("sequence", new JsonNumberText("1.0")),
                Arguments.of("prev_hash", List.of("not", "a", "string")),
                Arguments.of("signature", Map.of("not", "a string")),
                Arguments.of("signature", 7),
                Arguments.of("entry_hash", List.of("not a string")),
                Arguments.of("execution_id", new JsonNumberText("1")),
                Arguments.of("actor_id", "token:\uD800"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("retypedFields")
    void aFieldRustWouldNotReadIsMalformedNotACrash(String field, Object value) {
        Vector v = vector();
        v.entries().get(0).put(field, value);
        AuditChainException e = failure(v.entries(), v.publicKey());
        assertThat(e).isNotNull();
        assertThat(e.reason()).isEqualTo(AuditChainException.Reason.MALFORMED);
    }

    static Stream<UnaryOperator<String>> nonCanonicalHex() {
        return Stream.of(String::toUpperCase, h -> h.substring(0, 10) + " " + h.substring(10));
    }

    @ParameterizedTest
    @MethodSource("nonCanonicalHex")
    void signatureHexMustBeCanonical(UnaryOperator<String> mangle) {
        Vector v = vector();
        String sig = (String) v.entries().get(0).get("signature");
        int cut = sig.lastIndexOf(':');
        v.entries().get(0).put("signature", sig.substring(0, cut + 1) + mangle.apply(sig.substring(cut + 1)));
        assertThat(failure(v.entries(), v.publicKey()).reason()).isEqualTo(AuditChainException.Reason.BAD_SIGNATURE);
    }

    // -- serde_json text ---------------------------------------------------------------

    @Test
    void serdeJsonSortsByCodePointAndEscapesLikeSerde() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("😀", 1);       // U+1F600, a surrogate pair in UTF-16
        m.put("", 2);             // sorts first by code point, second by UTF-16 unit
        m.put("a", "q\"b\\s\u001f\u0007\t");
        assertThat(RuntimeAuditVerifier.serdeJson(m))
                .isEqualTo("{\"a\":\"q\\\"b\\\\s\\u001f\\u0007\\t\",\"\":2,\"😀\":1}");
    }

    // -- helpers -----------------------------------------------------------------------

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            be[i] = b[b.length - 1 - i];
        }
        return new BigInteger(1, be);
    }

    private static byte[] toLe32(BigInteger n) {
        byte[] be = n.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < be.length && i < 33; i++) {
            int src = be.length - 1 - i;
            if (i < 32) {
                out[i] = be[src];
            }
        }
        return out;
    }

    static boolean jdkAccepts(byte[] publicKey, byte[] message, byte[] signature) throws Exception {
        byte[] spki = new byte[44];
        System.arraycopy(HEX.parseHex("302a300506032b6570032100"), 0, spki, 0, 12);
        System.arraycopy(publicKey, 0, spki, 12, 32);
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
        s.update(message);
        try {
            return s.verify(signature);
        } catch (java.security.SignatureException e) {
            return false;
        }
    }

    /**
     * Control for the strict checks. The JDK's Ed25519 accepts a small-order R (so the
     * verifier's own check is what refuses it) and already refuses S + L (so the scalar check
     * is a second line, not the only one).
     */
    @Test
    void controlWhatTheJdkAloneAccepts() throws Exception {
        Vector v = vector();
        String hash = (String) v.entries().get(0).get("entry_hash");
        byte[] msg = RuntimeAuditVerifier.signingStatement(hash, v.kid());
        String good = (String) v.entries().get(0).get("signature");
        byte[] goodRaw = HEX.parseHex(good.substring(good.lastIndexOf(':') + 1));
        assertThat(jdkAccepts(v.publicKey(), msg, goodRaw)).as("control: the real signature").isTrue();

        String small = smallOrderRSignature(hash, v.kid(), v.publicKey());
        byte[] smallRaw = HEX.parseHex(small.substring(small.lastIndexOf(':') + 1));
        assertThat(jdkAccepts(v.publicKey(), msg, smallRaw)).as("JDK alone: small-order R").isTrue();

        byte[] malleable = goodRaw.clone();
        byte[] sPlusL = toLe32(le(Arrays.copyOfRange(goodRaw, 32, 64)).add(L));
        System.arraycopy(sPlusL, 0, malleable, 32, 32);
        assertThat(jdkAccepts(v.publicKey(), msg, malleable)).as("JDK alone: S + L").isFalse();
    }
}

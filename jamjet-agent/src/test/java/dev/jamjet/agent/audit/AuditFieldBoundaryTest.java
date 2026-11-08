package dev.jamjet.agent.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The content hash joins text fields with 0x1f and no lengths, so a 0x1f inside a field can
 * move a field boundary without changing the hash (review probe: actor_id
 * "mallory\u001fsystem", actor_type "human" re-split as actor_id "mallory", actor_type
 * "system"). Entries the runtime could not have written must not verify.
 */
class AuditFieldBoundaryTest {

    static KeyPair kp;
    static byte[] pub;

    @BeforeAll
    static void keys() throws Exception {
        kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = kp.getPublic().getEncoded();
        pub = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }

    static Map<String, Object> entry(String actorId, String actorType) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
        e.put("event_id", "01010101-0101-0101-0101-010101010101");
        e.put("execution_id", "exec_1");
        e.put("sequence", new JsonNumberText("0"));
        e.put("event_type", "approval_received");
        e.put("actor_id", actorId);
        e.put("actor_type", actorType);
        e.put("created_at", "2026-10-03T04:00:01Z");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", "approval_received");
        e.put("raw_event", raw);
        e.put("tenant_id", "default");
        e.put("redacted", false);
        return e;
    }

    /** Seals with a hash computed the way the runtime does, field by field. */
    static Map<String, Object> sealed(Map<String, Object> e) throws Exception {
        String h = contentHashLikeTheRuntime(e);
        String kid = RuntimeAuditVerifier.keyId(pub);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(RuntimeAuditVerifier.signingStatement(h, kid));
        e.put("entry_hash", h);
        e.put("signature", "ed25519:" + kid + ":" + HexFormat.of().formatHex(s.sign()));
        return e;
    }

    /** A test-side copy of AuditLogEntry::content_hash with no input checks. */
    static String contentHashLikeTheRuntime(Map<String, Object> e) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        java.util.function.Consumer<byte[]> f = b -> {
            md.update(b);
            md.update((byte) 0x1f);
        };
        HexFormat hex = HexFormat.of();
        f.accept(hex.parseHex(((String) e.get("id")).replace("-", "")));
        f.accept(hex.parseHex(((String) e.get("event_id")).replace("-", "")));
        f.accept(((String) e.get("execution_id")).getBytes());
        f.accept(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(Long.parseLong(((JsonNumberText) e.get("sequence")).text())).array());
        for (String k : List.of("event_type", "actor_id", "actor_type", "tool_call_hash", "policy_decision",
                "http_request_id", "http_method", "http_path", "ip_address")) {
            Object v = e.get(k);
            f.accept((v == null ? "" : (String) v).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String ts = (String) e.get("created_at");
        f.accept((ts.endsWith("Z") ? ts.substring(0, ts.length() - 1) + "+00:00" : ts).getBytes());
        f.accept(RuntimeAuditVerifier.serdeJson(e.get("raw_event")).getBytes());
        f.accept(((String) e.get("tenant_id")).getBytes());
        f.accept(new byte[0]);
        f.accept(new byte[]{0});
        f.accept(new byte[0]);
        return hex.formatHex(md.digest());
    }

    static AuditChainException.Reason reason(Map<String, Object> e) {
        AuditChainException ex = catchThrowableOfType(
                () -> RuntimeAuditVerifier.verifyEntries(List.of(e), pub), AuditChainException.class);
        return ex == null ? null : ex.reason();
    }

    @Test
    void aControlEntryVerifies() throws Exception {
        assertThat(reason(sealed(entry("token:7", "human")))).isNull();
    }

    @Test
    void aFieldHoldingTheSeparatorIsRefused() throws Exception {
        assertThat(reason(sealed(entry("mallory\u001fsystem", "human"))))
                .isEqualTo(AuditChainException.Reason.MALFORMED);
    }

    @Test
    void aBoundaryShiftedForgeryIsRefused() throws Exception {
        Map<String, Object> genuine = sealed(entry("mallory\u001fsystem", "human"));
        Map<String, Object> forged = new LinkedHashMap<>(genuine);
        forged.put("actor_id", "mallory");
        forged.put("actor_type", "system");
        forged.put("tool_call_hash", "human\u001f");
        assertThat(reason(forged)).isEqualTo(AuditChainException.Reason.MALFORMED);
    }

    @Test
    void anActorTypeRustCannotHoldIsRefused() throws Exception {
        assertThat(reason(sealed(entry("svc", "root")))).isEqualTo(AuditChainException.Reason.MALFORMED);
        assertThat(reason(sealed(entry("svc", "agent")))).isNull();
        assertThat(reason(sealed(entry("svc", "system")))).isNull();
    }

    @Test
    void aNonCanonicalIntegerIsRefused() throws Exception {
        Map<String, Object> e = sealed(entry("token:7", "human"));
        e.put("sequence", new JsonNumberText("-0"));
        assertThat(reason(e)).isEqualTo(AuditChainException.Reason.MALFORMED);
    }
}

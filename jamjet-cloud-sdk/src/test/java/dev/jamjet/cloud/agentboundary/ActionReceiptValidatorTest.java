package dev.jamjet.cloud.agentboundary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ActionReceiptValidator. Mirrors the Python test cases in
 * agentboundary/tests/test_validator_negative.py and test_validator_positive.py
 * to ensure cross-language conformance.
 */
class ActionReceiptValidatorTest {

    private ActionReceiptValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ActionReceiptValidator();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> minimalReceipt() {
        Map<String, Object> r = new HashMap<>();
        r.put("version", "agentboundary/v0.1");
        r.put("receipt_id", "11111111-2222-4333-8444-555555555555");
        r.put("issued_at", "2026-06-15T12:00:00Z");
        r.put("actor", new HashMap<>(Map.of("type", "human", "id", "u_alice")));
        r.put("agent", new HashMap<>(Map.of(
            "framework", "jamjet",
            "framework_version", "0.8.5",
            "model", "claude-opus-4-7")));
        r.put("tool", new HashMap<>(Map.of("name", "github-mcp", "capability", "github.merge")));
        r.put("target", new HashMap<>(Map.of(
            "system", "github.com/jamjet-labs/agentboundary",
            "environment", "prod")));
        r.put("arguments_hash", "a".repeat(64));
        r.put("policy", new HashMap<>(Map.of(
            "name", "prod-merges-require-approval",
            "version", "1",
            "decision", "allow")));
        r.put("execution", new HashMap<>(Map.of(
            "status", "success",
            "completed_at", "2026-06-15T12:00:01Z")));
        r.put("receipt_hash", "b".repeat(64));
        return r;
    }

    @Test
    void minimalReceiptValidates() {
        List<String> errors = validator.validate(minimalReceipt());
        assertThat(errors).isEmpty();
    }

    @Test
    void missingRequiredFieldFails() {
        Map<String, Object> bad = minimalReceipt();
        bad.remove("receipt_id");
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("receipt_id"));
    }

    @Test
    void wrongVersionLiteralFails() {
        Map<String, Object> bad = minimalReceipt();
        bad.put("version", "agentboundary/v0.2");
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("agentboundary/v0.1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidEnvironmentEnumFails() {
        Map<String, Object> bad = minimalReceipt();
        Map<String, Object> target = new HashMap<>((Map<String, Object>) bad.get("target"));
        target.put("environment", "preprod");
        bad.put("target", target);
        List<String> errors = validator.validate(bad);
        // networknt reports the valid enum values, not the invalid value; check the path instead
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("target.environment"));
    }

    @Test
    void shortArgumentsHashFails() {
        Map<String, Object> bad = minimalReceipt();
        bad.put("arguments_hash", "a".repeat(32));
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("arguments_hash"));
    }

    @Test
    void invalidUuidInReceiptIdFails() {
        Map<String, Object> bad = minimalReceipt();
        bad.put("receipt_id", "not-a-uuid");
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).containsIgnoringCase("uuid"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireApprovalDecisionWithoutApprovalBlockFails() {
        Map<String, Object> bad = minimalReceipt();
        Map<String, Object> policy = new HashMap<>((Map<String, Object>) bad.get("policy"));
        policy.put("decision", "require-approval");
        bad.put("policy", policy);
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("approval"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireApprovalWithApprovalBlockValidates() {
        Map<String, Object> receipt = minimalReceipt();
        Map<String, Object> policy = new HashMap<>((Map<String, Object>) receipt.get("policy"));
        policy.put("decision", "require-approval");
        receipt.put("policy", policy);
        receipt.put("approval", new HashMap<>(Map.of(
            "approver", Map.of("id", "u_bob", "role", "release-manager"),
            "approved_at", "2026-06-15T11:59:00Z")));
        List<String> errors = validator.validate(receipt);
        assertThat(errors).isEmpty();
    }

    @Test
    void additionalPropertiesAtRootAreRejected() {
        Map<String, Object> bad = minimalReceipt();
        bad.put("vendor_extension", Map.of("foo", "bar"));
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("vendor_extension"));
    }

    @Test
    void missingPolicyDoesNotSpuriouslyRequireApproval() {
        // Regression test for the vacuous-truth bug fixed in agentboundary commit 59a6806.
        // When policy is absent, validator must report 'policy required', NOT 'approval required'.
        Map<String, Object> bad = minimalReceipt();
        bad.remove("policy");
        List<String> errors = validator.validate(bad);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("policy"));
        assertThat(errors).noneSatisfy(e -> assertThat(e).contains("approval"));
    }
}

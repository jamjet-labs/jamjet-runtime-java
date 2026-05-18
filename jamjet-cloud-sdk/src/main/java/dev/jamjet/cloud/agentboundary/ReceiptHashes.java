package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared canonical-JSON SHA-256 utilities for AgentBoundary v0.1 Action Receipts.
 *
 * <p>Both the Spring AI Advisor and LangChain4j ToolExecutor integrations delegate here
 * so the hash algorithm is implemented exactly once.
 *
 * <p>Canonicalization scheme (AgentBoundary v0.1 spec §4.8):
 * Jackson serialization with {@code ORDER_MAP_ENTRIES_BY_KEYS=true} and
 * {@code Include.NON_NULL}, UTF-8 bytes, lowercase hex output.
 */
public final class ReceiptHashes {

    /**
     * Canonical JSON mapper: keys sorted, null values omitted.
     * Satisfies AgentBoundary v0.1 spec §4.8 canonicalization requirement
     * (RFC 8785-compatible via {@code ORDER_MAP_ENTRIES_BY_KEYS=true}).
     */
    static final ObjectMapper CANONICAL_MAPPER = createCanonicalMapper();

    private static ObjectMapper createCanonicalMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return m;
    }

    private ReceiptHashes() {}

    /**
     * Compute SHA-256 of the canonical JSON serialization of {@code input}.
     *
     * <p>Canonical JSON: Jackson with {@code ORDER_MAP_ENTRIES_BY_KEYS=true} and
     * {@code Include.NON_NULL}, no extra whitespace, UTF-8 bytes.
     * Output: lowercase hex, 64 characters.
     *
     * @param input any Jackson-serializable object
     * @return 64-character lowercase hex SHA-256 string
     */
    public static String canonicalJsonSha256Hex(Object input) {
        try {
            byte[] bytes = CANONICAL_MAPPER.writeValueAsBytes(input);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute canonical JSON SHA-256", e);
        }
    }

    /**
     * SHA-256 of the canonical JSON of the tool arguments string.
     *
     * <p>If {@code arguments} is null or blank, the empty JSON object {@code {}} is used.
     * If {@code arguments} is valid JSON it is first parsed and re-serialized canonically
     * to normalize whitespace and key order. If it cannot be parsed as JSON it is wrapped
     * as a canonical JSON string value.
     *
     * @param arguments raw JSON arguments string from the framework (may be null)
     * @return 64-character lowercase hex SHA-256 string
     */
    public static String computeArgumentsHash(String arguments) {
        try {
            Object parsed;
            if (arguments == null || arguments.isBlank()) {
                parsed = Map.of();
            } else {
                parsed = CANONICAL_MAPPER.readValue(arguments, Object.class);
            }
            return canonicalJsonSha256Hex(parsed);
        } catch (JsonProcessingException e) {
            // Fall back: treat as a plain string wrapped in canonical JSON
            return canonicalJsonSha256Hex(arguments);
        }
    }

    /**
     * SHA-256 of canonical JSON of all receipt fields EXCEPT {@code receipt_hash} itself.
     *
     * <p>Per AgentBoundary v0.1 spec §4.12: the hash input is the receipt content
     * serialized as canonical JSON (RFC 8785-compatible: {@code ORDER_MAP_ENTRIES_BY_KEYS=true},
     * {@code Include.NON_NULL}), encoded as UTF-8, digested with SHA-256, lowercase hex.
     *
     * <p>An auditor can independently verify by: serializing the full receipt JSON,
     * removing the {@code receipt_hash} field, re-canonicalizing, and comparing SHA-256.
     * This method produces the same bytes as that auditor path because it first round-trips
     * all sub-objects through Jackson (resolving {@code @JsonProperty} names), then applies
     * the canonical serialization — exactly mirroring what the auditor does.
     *
     * @param version      receipt schema version
     * @param receiptId    unique receipt identifier
     * @param issuedAt     ISO-8601 timestamp string
     * @param actor        actor who performed the action
     * @param agent        agent framework metadata
     * @param tool         tool that was invoked
     * @param target       target system/environment
     * @param argumentsHash pre-computed arguments_hash (SHA-256 hex of tool args)
     * @param policy       policy that governed the action
     * @param approval     optional human-approval record (null if not required)
     * @param execution    execution result metadata
     * @return 64-character lowercase hex SHA-256 string
     */
    @SuppressWarnings("unchecked")
    public static String computeReceiptHash(
            String version, String receiptId, String issuedAt,
            Actor actor, Agent agent, Tool tool, Target target,
            String argumentsHash, Policy policy, Approval approval, Execution execution) {
        // Build a preliminary Map using plain-string keys + POJOs. Serialize each sub-object
        // through CANONICAL_MAPPER so that @JsonProperty names (e.g. "framework_version") are
        // used instead of Java field names — exactly matching the receipt wire format.
        // Then round-trip the whole map through Jackson so nested POJOs become Map<String,Object>,
        // identical to what an external auditor would reconstruct from the receipt JSON.
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("version", version);
            raw.put("receipt_id", receiptId);
            raw.put("issued_at", issuedAt);
            raw.put("actor", actor);
            raw.put("agent", agent);
            raw.put("tool", tool);
            raw.put("target", target);
            raw.put("arguments_hash", argumentsHash);
            raw.put("policy", policy);
            if (approval != null) raw.put("approval", approval);
            raw.put("execution", execution);

            // Round-trip through canonical JSON: POJOs → JSON bytes → Map<String,Object>
            // This resolves all @JsonProperty annotations and produces the same Map structure
            // that an external auditor sees when deserializing the receipt JSON.
            String intermediateJson = CANONICAL_MAPPER.writeValueAsString(raw);
            Map<String, Object> canonicalMap = CANONICAL_MAPPER.readValue(intermediateJson, Map.class);

            return canonicalJsonSha256Hex(canonicalMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to compute receipt hash", e);
        }
    }
}

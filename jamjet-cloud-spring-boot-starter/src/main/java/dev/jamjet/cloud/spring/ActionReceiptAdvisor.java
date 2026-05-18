package dev.jamjet.cloud.spring;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.jamjet.cloud.agentboundary.ActionReceipt;
import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.Actor;
import dev.jamjet.cloud.agentboundary.ActorType;
import dev.jamjet.cloud.agentboundary.Agent;
import dev.jamjet.cloud.agentboundary.Approval;
import dev.jamjet.cloud.agentboundary.Execution;
import dev.jamjet.cloud.agentboundary.ExecutionStatus;
import dev.jamjet.cloud.agentboundary.LoggingActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.Policy;
import dev.jamjet.cloud.agentboundary.PolicyDecision;
import dev.jamjet.cloud.agentboundary.Target;
import dev.jamjet.cloud.agentboundary.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring AI {@link BaseAdvisor} that emits an AgentBoundary v0.1 Action Receipt
 * for every tool call produced by the model.
 *
 * <p>The advisor intercepts the {@link ChatClientResponse} in {@link #after}. For each
 * {@link AssistantMessage.ToolCall} present in the response's generations it builds a
 * fully-validated {@link ActionReceipt} and delegates to the configured
 * {@link ActionReceiptEmitter}.
 *
 * <p>v1.8 defaults:
 * <ul>
 *   <li>{@code agent.framework = "spring-ai"}</li>
 *   <li>{@code agent.framework_version = "1.0.0"}</li>
 *   <li>{@code policy.decision = ALLOW} (PolicyDecider is v1.9 work)</li>
 *   <li>{@code policy.name = "default.allow"}, {@code policy.version = "1"}</li>
 *   <li>{@code target.environment} derived from Spring {@link Environment} active profiles
 *       ({@code prod} / {@code staging} / {@code dev})</li>
 *   <li>{@code target.system} from {@code spring.application.name}</li>
 * </ul>
 *
 * <p>Emission failures are caught and logged at WARN — they never interrupt tool execution.
 *
 * <p>Usage:
 * <pre>{@code
 * ChatClient chatClient = ChatClient.builder(chatModel)
 *     .defaultAdvisors(new ActionReceiptAdvisor(environment))
 *     .build();
 * }</pre>
 */
public final class ActionReceiptAdvisor implements BaseAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(ActionReceiptAdvisor.class);

    private static final String FRAMEWORK = "spring-ai";
    private static final String FRAMEWORK_VERSION = "1.0.0";

    /**
     * Canonical JSON mapper: keys sorted, null values omitted.
     * Satisfies AgentBoundary v0.1 spec §4.8 canonicalization requirement
     * (RFC 8785-compatible via {@code ORDER_MAP_ENTRIES_BY_KEYS=true}).
     */
    private static final ObjectMapper CANONICAL_MAPPER = createCanonicalMapper();

    private static ObjectMapper createCanonicalMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return m;
    }

    private final ActionReceiptEmitter emitter;
    private final org.springframework.core.env.Environment springEnv;
    private final int order;

    /**
     * Construct an advisor that reads Spring environment metadata and emits via
     * {@link LoggingActionReceiptEmitter} (SLF4J at INFO).
     *
     * @param springEnv Spring {@link Environment} — used for profile + app name resolution
     */
    public ActionReceiptAdvisor(org.springframework.core.env.Environment springEnv) {
        this(springEnv, new LoggingActionReceiptEmitter(), org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    /**
     * Full constructor.
     *
     * @param springEnv Spring {@link Environment}
     * @param emitter   the {@link ActionReceiptEmitter} to deliver receipts to
     * @param order     Advisor precedence ({@link org.springframework.core.Ordered})
     */
    public ActionReceiptAdvisor(
            org.springframework.core.env.Environment springEnv,
            ActionReceiptEmitter emitter,
            int order) {
        if (springEnv == null) throw new IllegalArgumentException("springEnv must not be null");
        if (emitter == null) throw new IllegalArgumentException("emitter must not be null");
        this.springEnv = springEnv;
        this.emitter = emitter;
        this.order = order;
    }

    // -------------------------------------------------------------------------
    // BaseAdvisor contract
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "ActionReceiptAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Pass-through: this advisor only observes the response; it does not modify the request.
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    /**
     * Inspect the response for tool calls; emit one Action Receipt per tool call.
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        try {
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null) return response;

            List<Generation> generations = chatResponse.getResults();
            if (generations == null || generations.isEmpty()) return response;

            String completedAt = Instant.now().toString();
            String model = resolveModel(chatResponse);
            dev.jamjet.cloud.agentboundary.Environment env = resolveEnvironment();
            String system = resolveSystem();

            for (Generation gen : generations) {
                AssistantMessage msg = gen.getOutput();
                if (msg == null || !msg.hasToolCalls()) continue;
                for (AssistantMessage.ToolCall tc : msg.getToolCalls()) {
                    emitReceipt(tc, model, env, system, completedAt);
                }
            }
        } catch (Throwable t) {
            LOG.warn("ActionReceiptAdvisor.after failed — tool-call receipts may be missing", t);
        }
        return response;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void emitReceipt(
            AssistantMessage.ToolCall tc,
            String model,
            dev.jamjet.cloud.agentboundary.Environment env,
            String system,
            String completedAt) {
        try {
            String argsHash = computeArgumentsHash(tc.arguments());
            String receiptId = UUID.randomUUID().toString();
            String issuedAt = Instant.now().toString();

            Actor actor = new Actor(ActorType.AGENT, "spring-ai-agent", null);
            Agent agent = new Agent(FRAMEWORK, FRAMEWORK_VERSION, model, null);
            Tool tool = new Tool(tc.name(), null, tc.name());
            Target target = new Target(system, env, null);
            Policy policy = new Policy("default.allow", "1", PolicyDecision.ALLOW);
            Execution execution = new Execution(ExecutionStatus.SUCCESS, completedAt, null, null);

            // receipt_hash = SHA-256 of canonical JSON of all receipt fields EXCEPT receipt_hash itself.
            // Per AgentBoundary v0.1 spec §4.12: canonical JSON with ORDER_MAP_ENTRIES_BY_KEYS=true, NON_NULL.
            String receiptHash = computeReceiptHash(
                ActionReceipt.CURRENT_VERSION, receiptId, issuedAt,
                actor, agent, tool, target, argsHash, policy, null, execution);

            ActionReceipt receipt = new ActionReceipt(
                ActionReceipt.CURRENT_VERSION,
                receiptId,
                issuedAt,
                actor,
                agent,
                tool,
                target,
                argsHash,
                policy,
                null,       // no approval required for ALLOW decision
                execution,
                receiptHash
            );

            emitter.emit(receipt);
        } catch (Throwable t) {
            LOG.warn("ActionReceiptAdvisor: failed to build/emit receipt for tool '{}': {}",
                tc.name(), t.getMessage());
        }
    }

    /**
     * SHA-256 of the canonical JSON of the tool arguments.
     *
     * <p>Canonicalization scheme (AgentBoundary v0.1 spec §4.8, MUST document):
     * Jackson serialization with {@code ORDER_MAP_ENTRIES_BY_KEYS=true} and
     * {@code Include.NON_NULL}, UTF-8 bytes, lowercase hex output.
     * If {@code arguments} is null or blank the empty JSON object {@code {}} is used.
     */
    private static String computeArgumentsHash(String arguments) {
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
     */
    @SuppressWarnings("unchecked")
    private static String computeReceiptHash(
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

    /**
     * Compute SHA-256 of the canonical JSON serialization of {@code input}.
     *
     * <p>Canonical JSON: Jackson with {@code ORDER_MAP_ENTRIES_BY_KEYS=true} and
     * {@code Include.NON_NULL}, no extra whitespace, UTF-8 bytes.
     * Output: lowercase hex, 64 characters.
     */
    static String canonicalJsonSha256Hex(Object input) {
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

    private String resolveModel(ChatResponse chatResponse) {
        // Try to read the model from response metadata if available
        try {
            if (chatResponse.getMetadata() != null) {
                Object modelId = chatResponse.getMetadata().get("model");
                if (modelId instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) { /* defensive */ }
        return "unknown";
    }

    private dev.jamjet.cloud.agentboundary.Environment resolveEnvironment() {
        String[] profiles = springEnv.getActiveProfiles();
        for (String p : profiles) {
            if ("prod".equalsIgnoreCase(p)) return dev.jamjet.cloud.agentboundary.Environment.PROD;
            if ("staging".equalsIgnoreCase(p)) return dev.jamjet.cloud.agentboundary.Environment.STAGING;
        }
        return dev.jamjet.cloud.agentboundary.Environment.DEV;
    }

    private String resolveSystem() {
        String name = springEnv.getProperty("spring.application.name");
        return (name != null && !name.isBlank()) ? name : "spring-ai-app";
    }
}

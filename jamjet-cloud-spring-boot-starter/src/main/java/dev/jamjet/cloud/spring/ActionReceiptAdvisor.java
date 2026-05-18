package dev.jamjet.cloud.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.jamjet.cloud.agentboundary.ActionReceipt;
import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.Actor;
import dev.jamjet.cloud.agentboundary.ActorType;
import dev.jamjet.cloud.agentboundary.Agent;
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
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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

    private final ActionReceiptEmitter emitter;
    private final org.springframework.core.env.Environment springEnv;
    private final ObjectMapper sortedMapper;
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
        this.sortedMapper = new ObjectMapper();
        this.sortedMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
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

            // receipt_hash = SHA-256 of the JSON-serialized receipt without the receipt_hash field
            // We approximate by hashing key fields deterministically.
            String receiptHash = computeReceiptHash(receiptId, issuedAt, tc.name(), argsHash, completedAt);

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
     * SHA-256 of the canonical JSON of the tool arguments, with object keys sorted.
     * If arguments is null or empty JSON object/array, hashes the empty string.
     */
    private String computeArgumentsHash(String arguments) {
        String canonical = canonicalize(arguments);
        return sha256Hex(canonical);
    }

    /**
     * Canonicalize a JSON string by round-tripping through Jackson with sorted keys.
     * Falls back to the raw string on parse errors (e.g., plain string arguments).
     */
    @SuppressWarnings("unchecked")
    private String canonicalize(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            Object parsed = sortedMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?>) {
                TreeMap<String, Object> sorted = new TreeMap<>((Map<String, Object>) parsed);
                return sortedMapper.writeValueAsString(sorted);
            }
            return sortedMapper.writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /**
     * Deterministic receipt_hash over identifying fields (without the full POJO round-trip
     * to avoid circular dependency on the hash itself).
     */
    private String computeReceiptHash(
            String receiptId, String issuedAt, String toolName,
            String argsHash, String completedAt) {
        String input = receiptId + "|" + issuedAt + "|" + toolName + "|" + argsHash + "|" + completedAt;
        return sha256Hex(input);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
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

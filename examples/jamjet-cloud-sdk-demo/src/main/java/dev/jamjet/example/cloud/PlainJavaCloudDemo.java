package dev.jamjet.example.cloud;

import dev.jamjet.cloud.JamjetCloud;
import dev.jamjet.cloud.JamjetCloudConfig;
import dev.jamjet.cloud.Span;
import dev.jamjet.cloud.agentboundary.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Plain-Java (no Spring) demo of the raw JamJet Cloud SDK API:
 * open a span (observability) and emit an AgentBoundary receipt (audit).
 */
public final class PlainJavaCloudDemo {

    private PlainJavaCloudDemo() {}

    /** Testable entry point: configure the SDK, emit a span, build + emit a receipt. */
    public static void run(JamjetCloudConfig config, ActionReceiptEmitter emitter) {
        JamjetCloud.configure(config);

        // 1) Observability: a span for a (simulated) model call.
        Span span = JamjetCloud.newSpan("model", "demo-call");
        span.model("gpt-4o-mini").inputTokens(120).outputTokens(35).costUsd(0.0004);
        span.finish();

        // 2) Audit: an AgentBoundary Action Receipt for a (simulated) tool call.
        String argsJson = "{\"orderId\":\"A-100\"}";
        String argsHash = ReceiptHashes.computeArgumentsHash(argsJson);
        String receiptId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Actor actor = new Actor(ActorType.AGENT, "cloud-sdk-demo", null);
        Agent agent = new Agent("jamjet-runtime-java", "0.3.1", "gpt-4o-mini", null);
        Tool tool = new Tool("orderStatus", null, "orderStatus");
        Target target = new Target("cloud-sdk-demo", Environment.DEV, "A-100");
        Policy policy = new Policy("default.allow", "1", PolicyDecision.ALLOW);
        Execution execution = new Execution(ExecutionStatus.SUCCESS, now, null, "shipped");
        String receiptHash = ReceiptHashes.computeReceiptHash(
                ActionReceipt.CURRENT_VERSION, receiptId, now, actor, agent, tool, target,
                argsHash, policy, null, execution);
        ActionReceipt receipt = new ActionReceipt(ActionReceipt.CURRENT_VERSION, receiptId, now,
                actor, agent, tool, target, argsHash, policy, null, execution, receiptHash);
        java.util.List<String> errors = new ActionReceiptValidator().validate(receipt);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("receipt failed schema validation: " + errors);
        }
        emitter.emit(receipt);
    }

    public static void main(String[] args) {
        String apiKey = System.getenv().getOrDefault("JJ_API_KEY", "");
        String apiUrl = System.getenv().getOrDefault("JAMJET_API_URL", "https://api.jamjet.dev");
        Path receipts = Path.of(System.getProperty("user.home"), ".jamjet", "audit", "cloud-sdk-demo.jsonl");
        if (apiKey.isBlank()) {
            System.err.println("Set JJ_API_KEY to send the span to JamJet Cloud (api.jamjet.dev).");
        }
        JamjetCloudConfig cfg = JamjetCloudConfig.builder()
                .apiKey(apiKey).apiUrl(apiUrl).project("cloud-sdk-demo").build();
        run(cfg, new FileActionReceiptEmitter(receipts));
        System.out.println("Span sent to " + apiUrl + "; receipt written to " + receipts);
    }
}

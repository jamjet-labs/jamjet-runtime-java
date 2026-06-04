package dev.jamjet.example.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.cloud.agentboundary.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileActionReceiptEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static ActionReceipt sampleReceipt() {
        String argsJson = "{\"orderId\":\"A-100\"}";
        String argsHash = ReceiptHashes.computeArgumentsHash(argsJson);
        String receiptId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Actor actor = new Actor(ActorType.AGENT, "cloud-sdk-demo", null);
        Agent agent = new Agent("jamjet-runtime-java", "0.3.1", "demo-model", null);
        Tool tool = new Tool("orderStatus", null, "orderStatus");
        Target target = new Target("cloud-sdk-demo", Environment.DEV, "A-100");
        Policy policy = new Policy("default.allow", "1", PolicyDecision.ALLOW);
        Execution execution = new Execution(ExecutionStatus.SUCCESS, now, null, "shipped");
        String receiptHash = ReceiptHashes.computeReceiptHash(
                ActionReceipt.CURRENT_VERSION, receiptId, now, actor, agent, tool, target,
                argsHash, policy, null, execution);
        return new ActionReceipt(ActionReceipt.CURRENT_VERSION, receiptId, now, actor, agent, tool,
                target, argsHash, policy, null, execution, receiptHash);
    }

    @Test
    void appendsOneSchemaValidJsonLinePerReceipt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("receipts.jsonl");
        FileActionReceiptEmitter emitter = new FileActionReceiptEmitter(file);
        ActionReceipt receipt = sampleReceipt();

        emitter.emit(receipt);
        emitter.emit(receipt);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        // each line is valid JSON and the receipt validates against the v0.1 schema
        var parsed = MAPPER.readValue(lines.get(0), java.util.Map.class);
        @SuppressWarnings("unchecked")
        List<String> errors = new ActionReceiptValidator().validate((java.util.Map<String, Object>) parsed);
        assertThat(errors).isEmpty();
    }
}

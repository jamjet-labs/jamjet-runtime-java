package dev.jamjet.cloud.spring;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.jamjet.cloud.agentboundary.ActionReceipt;
import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.ActionReceiptValidator;
import dev.jamjet.cloud.agentboundary.ExecutionStatus;
import dev.jamjet.cloud.agentboundary.ReceiptHashes;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ActionReceiptToolExecutor}.
 *
 * <p>Uses a stub {@link ActionReceiptEmitter} to capture emitted receipts and a stub
 * {@link ToolExecutor} (returning a fixed string) — no real LangChain4j LLM or Spring
 * context needed.
 */
class ActionReceiptToolExecutorTest {

    private MockEnvironment mockEnv;
    private CapturingEmitter emitter;
    private StubToolExecutor stub;
    private ActionReceiptToolExecutor executor;
    private ActionReceiptValidator validator;

    @BeforeEach
    void setUp() {
        mockEnv = new MockEnvironment();
        mockEnv.setProperty("spring.application.name", "test-app");

        emitter = new CapturingEmitter();
        stub = new StubToolExecutor("OK");
        executor = new ActionReceiptToolExecutor(stub, mockEnv, emitter);
        validator = new ActionReceiptValidator();
    }

    // -------------------------------------------------------------------------
    // Basic emission
    // -------------------------------------------------------------------------

    @Test
    void emitsExactlyOneReceiptPerExecute() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("github.merge")
            .arguments("{\"pr\": 42}")
            .build();

        String result = executor.execute(request, "session-1");

        assertThat(result).isEqualTo("OK");
        assertThat(emitter.receipts).hasSize(1);
    }

    @Test
    void emittedReceiptHasCorrectToolName() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("database.query")
            .arguments("{\"sql\": \"SELECT 1\"}")
            .build();

        executor.execute(request, "session-1");

        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.tool().name()).isEqualTo("database.query");
        assertThat(receipt.tool().capability()).isEqualTo("database.query");
    }

    @Test
    void emittedReceiptHasLangchain4jFramework() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("search")
            .arguments("{}")
            .build();

        executor.execute(request, "session-1");

        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.agent().framework()).isEqualTo("langchain4j");
    }

    @Test
    void emittedReceiptHasSuccessStatus() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("noop")
            .arguments("{}")
            .build();

        executor.execute(request, "session-1");

        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.execution().status()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Schema validation
    // -------------------------------------------------------------------------

    @Test
    void emittedReceiptPassesSchemaValidation() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("github.merge")
            .arguments("{\"pr\": 42}")
            .build();

        executor.execute(request, "session-1");

        assertThat(emitter.receipts).hasSize(1);
        List<String> errors = validator.validate(emitter.receipts.get(0));
        assertThat(errors)
            .as("Receipt schema validation errors: %s", errors)
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Spec-compliance: receipt_hash must be independently verifiable (§4.12)
    // -------------------------------------------------------------------------

    /**
     * AgentBoundary v0.1 spec §4.12: the receipt_hash is SHA-256 of the canonicalized
     * receipt content excluding the receipt_hash field itself.
     */
    @Test
    void receiptHashIsIndependentlyVerifiablePerSpec() throws Exception {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("database.query")
            .arguments("{\"sql\": \"SELECT * FROM users\", \"limit\": 100}")
            .build();

        executor.execute(request, "session-1");

        assertThat(emitter.receipts).hasSize(1);
        ActionReceipt receipt = emitter.receipts.get(0);

        ObjectMapper canonicalMapper = new ObjectMapper();
        canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        canonicalMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // Step 1: serialize the full receipt to a Map (round-trip resolves @JsonProperty names)
        String fullJson = canonicalMapper.writeValueAsString(receipt);
        @SuppressWarnings("unchecked")
        Map<String, Object> receiptMap = canonicalMapper.readValue(fullJson, Map.class);

        // Step 2: remove the receipt_hash field
        receiptMap.remove("receipt_hash");

        // Step 3: canonical-JSON-SHA-256 the remaining content
        String recomputedHash = ReceiptHashes.canonicalJsonSha256Hex(receiptMap);

        // Assert: auditor-recomputed hash matches emitted hash
        assertThat(recomputedHash)
            .as("Auditor-recomputed receipt_hash must match emitted receipt_hash (AgentBoundary §4.12)")
            .isEqualTo(receipt.receiptHash());
    }

    // -------------------------------------------------------------------------
    // Failure case
    // -------------------------------------------------------------------------

    @Test
    void whenInnerThrowsEmitterStillReceivesFailureReceipt() {
        StubToolExecutor failingStub = new StubToolExecutor(new RuntimeException("boom"));
        ActionReceiptToolExecutor failingExecutor =
            new ActionReceiptToolExecutor(failingStub, mockEnv, emitter);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("risky.op")
            .arguments("{\"target\": \"prod\"}")
            .build();

        assertThatThrownBy(() -> failingExecutor.execute(request, "session-1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("boom");

        assertThat(emitter.receipts).hasSize(1);
        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.execution().status()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(receipt.execution().errorCode()).isEqualTo("RuntimeException");
    }

    // -------------------------------------------------------------------------
    // Environment / system resolution
    // -------------------------------------------------------------------------

    @Test
    void targetSystemFromSpringApplicationName() {
        mockEnv.setProperty("spring.application.name", "my-langchain4j-agent");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("tool1")
            .arguments("{}")
            .build();

        executor.execute(request, "session-1");

        assertThat(emitter.receipts.get(0).target().system()).isEqualTo("my-langchain4j-agent");
    }

    @Test
    void targetEnvironmentProdWhenProdProfileActive() {
        mockEnv.setActiveProfiles("prod");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("tool1")
            .arguments("{}")
            .build();

        executor.execute(request, "session-1");

        assertThat(emitter.receipts.get(0).target().environment())
            .isEqualTo(dev.jamjet.cloud.agentboundary.Environment.PROD);
    }

    @Test
    void targetEnvironmentDefaultsToDevWhenNoProfile() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .name("tool1")
            .arguments("{}")
            .build();

        executor.execute(request, "session-1");

        assertThat(emitter.receipts.get(0).target().environment())
            .isEqualTo(dev.jamjet.cloud.agentboundary.Environment.DEV);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final class CapturingEmitter implements ActionReceiptEmitter {
        final List<ActionReceipt> receipts = new ArrayList<>();

        @Override
        public void emit(ActionReceipt receipt) {
            receipts.add(receipt);
        }
    }

    private static final class StubToolExecutor implements ToolExecutor {
        private final String result;
        private final RuntimeException error;

        StubToolExecutor(String result) {
            this.result = result;
            this.error = null;
        }

        StubToolExecutor(RuntimeException error) {
            this.result = null;
            this.error = error;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            if (error != null) throw error;
            return result;
        }
    }
}

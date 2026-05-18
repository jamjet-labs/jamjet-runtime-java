package dev.jamjet.cloud.spring;

import dev.jamjet.cloud.agentboundary.ActionReceipt;
import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import dev.jamjet.cloud.agentboundary.ActionReceiptValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ActionReceiptAdvisor}.
 *
 * <p>Uses a stub {@link ActionReceiptEmitter} to capture emitted receipts without
 * needing a running Spring context or real LLM. The test constructs synthetic
 * {@link ChatClientResponse} objects that carry tool-call metadata and verifies
 * the advisor produces valid, schema-conformant Action Receipts.
 */
class ActionReceiptAdvisorTest {

    private MockEnvironment mockEnv;
    private CapturingEmitter emitter;
    private ActionReceiptAdvisor advisor;
    private AdvisorChain mockChain;
    private ActionReceiptValidator validator;

    @BeforeEach
    void setUp() {
        mockEnv = new MockEnvironment();
        mockEnv.setProperty("spring.application.name", "test-app");

        emitter = new CapturingEmitter();
        advisor = new ActionReceiptAdvisor(mockEnv, emitter, 0);
        mockChain = mock(AdvisorChain.class);
        validator = new ActionReceiptValidator();
    }

    // -------------------------------------------------------------------------
    // before() — pass-through
    // -------------------------------------------------------------------------

    @Test
    void beforePassesThroughRequestUnmodified() {
        Prompt prompt = new Prompt("hello");
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();
        ChatClientRequest result = advisor.before(request, mockChain);
        assertThat(result).isSameAs(request);
    }

    // -------------------------------------------------------------------------
    // after() — no tool calls
    // -------------------------------------------------------------------------

    @Test
    void afterDoesNotEmitWhenNoToolCalls() {
        AssistantMessage msg = new AssistantMessage("Just a text reply", Map.of(), List.of());
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts).isEmpty();
    }

    @Test
    void afterDoesNotEmitWhenChatResponseIsNull() {
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(null).build();
        advisor.after(response, mockChain);
        assertThat(emitter.receipts).isEmpty();
    }

    @Test
    void afterDoesNotEmitWhenGenerationsEmpty() {
        ChatResponse chatResponse = new ChatResponse(Collections.emptyList());
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts).isEmpty();
    }

    // -------------------------------------------------------------------------
    // after() — with tool calls
    // -------------------------------------------------------------------------

    @Test
    void afterEmitsOneReceiptPerToolCall() {
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "github.merge", "{\"pr\": 42}"),
            new AssistantMessage.ToolCall("call2", "function", "send_email", "{\"to\": \"user@example.com\"}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts).hasSize(2);
    }

    @Test
    void emittedReceiptHasCorrectToolName() {
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "database.query", "{\"sql\": \"SELECT 1\"}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts).hasSize(1);
        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.tool().name()).isEqualTo("database.query");
        assertThat(receipt.tool().capability()).isEqualTo("database.query");
    }

    @Test
    void emittedReceiptHasSpringAiFramework() {
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "search", "{}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.agent().framework()).isEqualTo("spring-ai");
        assertThat(receipt.agent().frameworkVersion()).isEqualTo("1.0.0");
    }

    @Test
    void emittedReceiptHasAlwaysAllow() {
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "noop", "{}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        ActionReceipt receipt = emitter.receipts.get(0);
        assertThat(receipt.policy().name()).isEqualTo("default.allow");
        assertThat(receipt.policy().decision()).isEqualTo(dev.jamjet.cloud.agentboundary.PolicyDecision.ALLOW);
    }

    @Test
    void emittedReceiptTargetSystemFromAppName() {
        mockEnv.setProperty("spring.application.name", "my-agent-service");

        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "tool1", "{}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts.get(0).target().system()).isEqualTo("my-agent-service");
    }

    @Test
    void emittedReceiptTargetEnvironmentFromActiveProfile() {
        mockEnv.setActiveProfiles("prod");

        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "tool1", "{}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts.get(0).target().environment())
            .isEqualTo(dev.jamjet.cloud.agentboundary.Environment.PROD);
    }

    @Test
    void emittedReceiptEnvironmentDefaultsToDevWhenNoProfile() {
        // no active profile set
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "tool1", "{}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts.get(0).target().environment())
            .isEqualTo(dev.jamjet.cloud.agentboundary.Environment.DEV);
    }

    @Test
    void emittedReceiptHasValidArgumentsHash() {
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "tool1", "{\"a\": 1}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        // arguments_hash must be 64 lowercase hex chars
        String argsHash = emitter.receipts.get(0).argumentsHash();
        assertThat(argsHash).matches("^[a-f0-9]{64}$");
    }

    @Test
    void emittedReceiptPassesSchemaValidation() {
        AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(
            new AssistantMessage.ToolCall("call1", "function", "github.merge", "{\"pr\": 42}")
        ));
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        advisor.after(response, mockChain);

        assertThat(emitter.receipts).hasSize(1);
        List<String> errors = validator.validate(emitter.receipts.get(0));
        assertThat(errors)
            .as("Receipt schema validation errors: %s", errors)
            .isEmpty();
    }

    @Test
    void afterPassesThroughResponseUnmodified() {
        AssistantMessage msg = new AssistantMessage("hello", Map.of(), List.of());
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).build();

        ChatClientResponse result = advisor.after(response, mockChain);

        assertThat(result).isSameAs(response);
    }

    @Test
    void advisorNameIsActionReceiptAdvisor() {
        assertThat(advisor.getName()).isEqualTo("ActionReceiptAdvisor");
    }

    // -------------------------------------------------------------------------
    // Helper: in-memory emitter
    // -------------------------------------------------------------------------

    private static final class CapturingEmitter implements ActionReceiptEmitter {
        final List<ActionReceipt> receipts = new ArrayList<>();

        @Override
        public void emit(ActionReceipt receipt) {
            receipts.add(receipt);
        }
    }
}

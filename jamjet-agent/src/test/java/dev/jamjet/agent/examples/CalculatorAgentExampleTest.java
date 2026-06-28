package dev.jamjet.agent.examples;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.jamjet.agent.Agent;
import dev.jamjet.agent.AgentResult;
import dev.jamjet.agent.RunOptions;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.tools.ToolDispatcher;
import dev.jamjet.agent.worker.JavaToolWorker;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.ir.NodeKind;
import dev.jamjet.runtime.core.ir.WorkflowIr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic END-TO-END proof for the {@link CalculatorAgentExample}: the idiomatic Java
 * agent builds, compiles to the agent-loop IR, has its {@code @Tool} executed durably by
 * a {@link JavaToolWorker}, and {@code runDurable} returns the expected governed result —
 * all against a WireMock stub of the engine's bare routes (a live Rust engine + sidecar
 * is not available in CI).
 *
 * <p><b>Parity with the Python equivalent.</b> The asserted result shape matches the
 * Python {@code agent.run_durable} result: the {@link AgentResult#output()} is the final
 * assistant text, and the tool actually ran (proven twice — the worker's completion body
 * carries the tool's output, and the reconstructed {@link AgentResult#toolCalls()} carries
 * the call + its result).
 *
 * <p>Because WireMock cannot run the engine's scheduler, the two halves of the durable
 * loop are stubbed independently and asserted together: (1) the {@link JavaToolWorker}
 * draining a stubbed {@code java_tool} claim dispatches the real {@code @Tool}; (2)
 * {@code runDurable} extracts the final answer from the terminal {@code current_state}.
 */
class CalculatorAgentExampleTest {

    WireMockServer wm;

    @BeforeEach
    void up() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void down() {
        wm.stop();
    }

    @Test
    @Timeout(20)
    void buildsCompilesDispatchesToolDurablyAndReturnsGovernedResult() {
        Agent agent = CalculatorAgentExample.buildAgent();

        // -- (a) the example compiles to a governed agent-loop IR -------------------
        WorkflowIr ir = agent.compileToIr(2);
        assertThat(ir.startNode()).isEqualTo("__model_0__");                 // M2: start is a Model node
        assertThat(ir.costBudgetUsd()).isEqualTo(0.50);                      // governance rode into the IR
        assertThat(ir.tokenBudget().totalTokens()).isEqualTo(50_000);
        assertThat(ir.policy().blockedTools()).contains("delete_*");
        assertThat(ir.policy().requireApprovalFor()).contains("wire_*");
        assertThat(ir.dataPolicy().piiDetectors()).hasSize(5);

        // -- (b) DRY: the worker accepts EXACTLY the coordinate the compiler emits ---
        NodeKind.JavaFn toolNode = (NodeKind.JavaFn) ir.node("__tools_0__").kind();
        // The emitted JavaFn coordinate is the same literal the worker's RCE gate checks.
        assertThat(toolNode.className()).isEqualTo(ToolDispatcher.DISPATCH_CLASS);
        assertThat(toolNode.method()).isEqualTo(ToolDispatcher.DISPATCH_METHOD);

        // -- stubs: the bare engine routes for BOTH the worker and the run ----------
        Map<String, Object> dispatchInput = Map.of(
                "last_model_tool_calls",
                List.of(toolCall("tc1", "calculate", Map.of("a", 5, "b", 3, "op", "add"))));
        // The claim carries the coordinate the COMPILER emitted (not a hardcoded literal),
        // so a green dispatch proves the worker accepts the builder's emitted coordinate.
        wm.stubFor(post(urlEqualTo("/work-items/claim"))
                .willReturn(okJson(claim(toolNode.className(), toolNode.method(), dispatchInput, 7L))));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/heartbeat")).willReturn(ok()));

        wm.stubFor(post(urlEqualTo("/workflows"))
                .willReturn(okJson("{\"workflow_id\":\"calculator_agent\",\"version\":\"" + ir.version() + "\"}")));
        wm.stubFor(post(urlEqualTo("/executions"))
                .willReturn(okJson("{\"execution_id\":\"ex_1\",\"status\":\"running\"}")));
        wm.stubFor(get(urlEqualTo("/executions/ex_1"))
                .willReturn(okJson(completedBody())));

        RunOptions runOptions = RunOptions.defaults()
                .withMaxTurns(2)
                .withRuntimeUrl(wm.baseUrl())
                .withPollInterval(Duration.ofMillis(15))
                .withTimeout(Duration.ofSeconds(5));

        AgentResult result;
        try (JamjetEngineClient client = new JamjetEngineClient(wm.baseUrl());
             JavaToolWorker worker = new JavaToolWorker(client, "calculator-worker-1", agent.registry(),
                     Duration.ofSeconds(2), Duration.ofMillis(10))) {

            // -- (c) the @Tool runs DURABLY: the worker dispatches the claimed java_tool item.
            JavaToolWorker.ItemResult dispatched = worker.runOnce();
            assertThat(dispatched).isEqualTo(JavaToolWorker.ItemResult.COMPLETED);

            // -- (d) the durable run extracts the final governed answer -------------
            result = agent.runDurable("What is (5 + 3) * 2?", client, runOptions);
        }

        // The tool actually ran: calculate(5, 3, "add") -> "8" in the completion body.
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/complete"))
                .withRequestBody(matchingJsonPath("$.lease_fence", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.output.messages[1].role", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.output.messages[1].content", equalTo("8"))));

        // The result PARALLELS the Python run_durable result: final assistant text + the tool call.
        assertThat(result.output()).isEqualTo("(5 + 3) * 2 = 16");
        assertThat(result.terminalState().status()).isEqualTo("completed");
        assertThat(result.toolCalls()).hasSize(1);
        AgentResult.ToolCall call = result.toolCalls().get(0);
        assertThat(call.tool()).isEqualTo("calculate");
        assertThat(call.input()).containsEntry("op", "add");
        assertThat(call.output()).isEqualTo("8");
    }

    // -- helpers ----------------------------------------------------------------

    private static Map<String, Object> toolCall(String id, String name, Map<String, Object> args) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("arguments", args);
        return c;
    }

    /** A {@code {claimed, work_item}} claim response carrying the given dispatch coordinate + input. */
    private static String claim(String dispatchClass, String dispatchMethod, Map<String, Object> input, long fence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("class", dispatchClass);
        payload.put("method", dispatchMethod);
        payload.put("input", input);

        Map<String, Object> wi = new LinkedHashMap<>();
        wi.put("id", "wi_1");
        wi.put("execution_id", "ex_1");
        wi.put("node_id", "__tools_0__");
        wi.put("queue_type", "java_tool");
        wi.put("payload", payload);
        wi.put("attempt", 1);
        wi.put("lease_fence", fence);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("claimed", true);
        resp.put("work_item", wi);
        try {
            return JamjetJson.shared().writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A terminal completed snapshot: inline last_model_output + a calculate tool turn. */
    private static String completedBody() {
        return "{\"execution_id\":\"ex_1\",\"status\":\"completed\",\"current_state\":{"
                + "\"last_model_output\":\"(5 + 3) * 2 = 16\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are a precise calculator.\"},"
                + "{\"role\":\"user\",\"content\":\"What is (5 + 3) * 2?\"},"
                + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"tc1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"calculate\",\"arguments\":\"{\\\"a\\\":5,\\\"b\\\":3,\\\"op\\\":\\\"add\\\"}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"tc1\",\"name\":\"calculate\",\"content\":\"8\"}"
                + "]}}";
    }
}

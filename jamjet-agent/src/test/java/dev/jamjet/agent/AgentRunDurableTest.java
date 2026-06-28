package dev.jamjet.agent;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.jamjet.agent.client.JamjetEngineClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hermetic integration test for {@link Agent#runDurable} against a WireMock stub of the
 * engine's bare routes ({@code /workflows}, {@code /executions}, {@code /executions/{id}}).
 * Proves the B-4 durable-run contract end-to-end at the transport boundary:
 *
 * <ul>
 *   <li><b>create &rarr; start &rarr; poll &rarr; extract</b> — the workflow is created,
 *       the execution is started with the seeded {@code messages} + tool-resolver map and
 *       the content-hashed {@code workflow_version}, the run polls to a terminal state, and
 *       the final assistant text + tool-call trace are extracted into the
 *       {@link AgentResult}.</li>
 *   <li><b>terminal failure surfaces</b> — a {@code failed} / {@code limit_exceeded}
 *       terminal raises a clear {@link AgentRunException} (mirrors the Python
 *       {@code RuntimeError}); never a hollow result.</li>
 *   <li><b>timeout surfaces</b> — a run that never terminates raises
 *       {@link AgentRunTimeoutException} (mirrors the Python {@code TimeoutError}).</li>
 *   <li><b>last-assistant fallback</b> — when {@code last_model_output} is absent the
 *       answer falls back to the last assistant message's content.</li>
 * </ul>
 *
 * <p>The status strings are the real snake_case the Rust engine emits
 * ({@code running} / {@code completed} / {@code failed} / {@code limit_exceeded};
 * {@code runtime/core/src/workflow.rs}), not capitalized stubs.
 */
class AgentRunDurableTest {

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

    /** A small governed agent with two @Tool methods (mirrors the golden fixture). */
    private static Agent demoAgent() {
        return Agent.builder("research_agent")
                .model("anthropic/claude-sonnet-4-6")
                .instructions("You are a helpful research assistant.")
                .tools(new TestTools.WebSearchTool(), new TestTools.MathTool())
                .budget(new Budget(100_000, 2.5))
                .build();
    }

    /** Run options pinned at the WireMock base URL with a fast poll + bounded timeout. */
    private RunOptions fastOptions() {
        return RunOptions.defaults()
                .withMaxTurns(2)
                .withRuntimeUrl(wm.baseUrl())
                .withPollInterval(Duration.ofMillis(15))
                .withTimeout(Duration.ofSeconds(5));
    }

    @Test
    @Timeout(20)
    void createStartPollExtractFinalAssistantText() {
        Agent agent = demoAgent();
        // The version runDurable will send on start is the content hash of the SAME IR.
        String expectedVersion = agent.compileToIr(2).version();

        wm.stubFor(post(urlEqualTo("/workflows"))
                .willReturn(okJson("{\"workflow_id\":\"research_agent\",\"version\":\"" + expectedVersion + "\"}")));
        wm.stubFor(post(urlEqualTo("/executions"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"running\"}")));

        // First poll -> running, second poll -> completed (prove the loop actually polls).
        wm.stubFor(get(urlEqualTo("/executions/ex_99")).inScenario("poll")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"running\"}"))
                .willSetStateTo("done"));
        wm.stubFor(get(urlEqualTo("/executions/ex_99")).inScenario("poll")
                .whenScenarioStateIs("done")
                .willReturn(okJson(completedBody())));

        AgentResult result;
        try (var client = new JamjetEngineClient(wm.baseUrl())) {
            result = agent.runDurable("add 2 and 3", client, fastOptions());
        }

        // The answer is the terminal current_state.last_model_output (the inline final turn).
        assertThat(result.output()).isEqualTo("The sum of 2 and 3 is 5.");
        // The tool-call trace is reconstructed from the accumulated messages.
        assertThat(result.toolCalls()).hasSize(1);
        AgentResult.ToolCall call = result.toolCalls().get(0);
        assertThat(call.tool()).isEqualTo("add_numbers");
        assertThat(call.input()).containsEntry("a", 2).containsEntry("b", 3);
        assertThat(call.output()).isEqualTo("5");
        // The raw terminal snapshot is carried through.
        assertThat(result.terminalState().status()).isEqualTo("completed");

        // CREATE wire shape: the compiled agent-loop IR, java_fn tool node, M2 start node.
        wm.verify(postRequestedFor(urlEqualTo("/workflows"))
                .withRequestBody(matchingJsonPath("$.ir.workflow_id", equalTo("research_agent")))
                .withRequestBody(matchingJsonPath("$.ir.start_node", equalTo("__model_0__")))
                .withRequestBody(matchingJsonPath("$.ir.nodes.__tools_0__.kind.type", equalTo("java_fn")))
                .withRequestBody(matchingJsonPath("$.ir.nodes.__tools_0__.kind.class_name",
                        equalTo("dev.jamjet.agent.tools.ToolDispatcher"))));
        // START wire shape: workflow_version = the content hash; the seeded user prompt +
        // tool-resolver map are the execution input (build_initial_state).
        wm.verify(postRequestedFor(urlEqualTo("/executions"))
                .withRequestBody(matchingJsonPath("$.workflow_id", equalTo("research_agent")))
                .withRequestBody(matchingJsonPath("$.workflow_version", equalTo(expectedVersion)))
                .withRequestBody(matchingJsonPath("$.input.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.input.messages[1].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.input.messages[1].content", equalTo("add 2 and 3")))
                .withRequestBody(matchingJsonPath("$.input.tools.add_numbers")));
    }

    @Test
    @Timeout(20)
    void terminalFailedSurfacesAsAgentRunException() {
        stubCreateAndStart();
        wm.stubFor(get(urlEqualTo("/executions/ex_99"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"failed\","
                        + "\"current_state\":{\"error\":\"model adapter exploded\"}}")));

        try (var client = new JamjetEngineClient(wm.baseUrl())) {
            assertThatThrownBy(() -> demoAgent().runDurable("hi", client, fastOptions()))
                    .isInstanceOf(AgentRunException.class)
                    .hasMessageContaining("non-completed")
                    .hasMessageContaining("model adapter exploded")
                    .extracting(e -> ((AgentRunException) e).status())
                    .isEqualTo("failed");
        }
    }

    @Test
    @Timeout(20)
    void terminalLimitExceededSurfacesAsAgentRunException() {
        // A budget breach is a terminal limit_exceeded — it must surface loudly, not as a result.
        stubCreateAndStart();
        wm.stubFor(get(urlEqualTo("/executions/ex_99"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"limit_exceeded\"}")));

        try (var client = new JamjetEngineClient(wm.baseUrl())) {
            assertThatThrownBy(() -> demoAgent().runDurable("hi", client, fastOptions()))
                    .isInstanceOf(AgentRunException.class)
                    .extracting(e -> ((AgentRunException) e).status())
                    .isEqualTo("limit_exceeded");
        }
    }

    @Test
    @Timeout(20)
    void neverTerminalRaisesTimeout() {
        stubCreateAndStart();
        wm.stubFor(get(urlEqualTo("/executions/ex_99"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"running\"}")));

        RunOptions tinyTimeout = fastOptions().withTimeout(Duration.ofMillis(120));
        try (var client = new JamjetEngineClient(wm.baseUrl())) {
            assertThatThrownBy(() -> demoAgent().runDurable("hi", client, tinyTimeout))
                    .isInstanceOf(AgentRunTimeoutException.class)
                    .extracting(e -> ((AgentRunException) e).status())
                    .isEqualTo("running");
        }
    }

    @Test
    @Timeout(20)
    void twoArgOverloadBuildsAndClosesItsOwnClient() {
        // The common public path: runDurable(prompt, options) builds + closes its own
        // JamjetEngineClient for options.runtimeUrl(). Point it at WireMock end-to-end.
        stubCreateAndStart();
        wm.stubFor(get(urlEqualTo("/executions/ex_99"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"completed\","
                        + "\"current_state\":{\"last_model_output\":\"done\"}}")));

        AgentResult result = demoAgent().runDurable("hi", fastOptions());

        assertThat(result.output()).isEqualTo("done");
        wm.verify(postRequestedFor(urlEqualTo("/workflows")));
        wm.verify(postRequestedFor(urlEqualTo("/executions")));
    }

    @Test
    @Timeout(20)
    void extractsFromLastAssistantMessageWhenNoLastModelOutput() {
        stubCreateAndStart();
        // No last_model_output; the answer must fall back to the last assistant message.
        wm.stubFor(get(urlEqualTo("/executions/ex_99"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"completed\","
                        + "\"current_state\":{\"messages\":["
                        + "{\"role\":\"user\",\"content\":\"hi\"},"
                        + "{\"role\":\"assistant\",\"content\":\"fallback answer\"}]}}")));

        try (var client = new JamjetEngineClient(wm.baseUrl())) {
            AgentResult result = demoAgent().runDurable("hi", client, fastOptions());
            assertThat(result.output()).isEqualTo("fallback answer");
            assertThat(result.toolCalls()).isEmpty();
        }
    }

    // -- helpers ----------------------------------------------------------------

    private void stubCreateAndStart() {
        wm.stubFor(post(urlEqualTo("/workflows"))
                .willReturn(okJson("{\"workflow_id\":\"research_agent\",\"version\":\"v1\"}")));
        wm.stubFor(post(urlEqualTo("/executions"))
                .willReturn(okJson("{\"execution_id\":\"ex_99\",\"status\":\"running\"}")));
    }

    /** A terminal completed snapshot: an inline last_model_output + a tool-interleaved thread. */
    private static String completedBody() {
        return "{\"execution_id\":\"ex_99\",\"status\":\"completed\",\"current_state\":{"
                + "\"last_model_output\":\"The sum of 2 and 3 is 5.\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are a helpful research assistant.\"},"
                + "{\"role\":\"user\",\"content\":\"add 2 and 3\"},"
                + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"tc1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"add_numbers\",\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"tc1\",\"name\":\"add_numbers\",\"content\":\"5\"}"
                + "]}}";
    }
}

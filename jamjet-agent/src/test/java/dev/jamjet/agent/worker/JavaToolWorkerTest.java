package dev.jamjet.agent.worker;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.jamjet.agent.TestTools;
import dev.jamjet.agent.Tool;
import dev.jamjet.agent.client.JamjetEngineClient;
import dev.jamjet.agent.tools.ToolDispatcher;
import dev.jamjet.agent.tools.ToolRegistry;
import dev.jamjet.runtime.core.JamjetJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial integration tests for {@link JavaToolWorker} against a WireMock stub of
 * the engine's bare work-item routes. This is the enforcement surface (a stale/zombie
 * worker must not complete a reclaimed item), so every guard is exercised end-to-end
 * over real HTTP:
 *
 * <ul>
 *   <li><b>Happy path</b> &mdash; claim &rarr; dispatch a registered {@code @Tool}
 *       &rarr; complete WITH the lease fence (asserted in the request body).</li>
 *   <li><b>Fence-lost mid-flight (M3)</b> &mdash; a heartbeat is rejected while a tool
 *       runs &rarr; the worker aborts the tool and NEVER calls complete.</li>
 *   <li><b>409 on complete = lost lease</b> &mdash; the worker does NOT fail the item.</li>
 *   <li><b>Unknown dispatch coordinate = clean fail, not RCE</b> &mdash; a forged
 *       {@code java_fn} payload class is failed cleanly, never {@code Class.forName}'d.</li>
 *   <li><b>Arg coercion</b> &mdash; JSON args coerce to typed params end-to-end.</li>
 * </ul>
 */
class JavaToolWorkerTest {

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

    // -- fixtures ---------------------------------------------------------------

    /** A tool that blocks until interrupted, so the M3 heartbeat abort can hit it mid-flight. */
    public static final class SlowTool {
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean interrupted = false;

        @Tool(name = "slow_tool", description = "blocks until interrupted")
        public String slow(String ignored) {
            started.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while running slow_tool");
            }
            return "should never be reached";
        }
    }

    private static Map<String, Object> toolCall(String id, String name, Map<String, Object> args) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("arguments", args);
        return c;
    }

    /** Serialize a {@code {claimed, work_item}} claim response with the given payload + fence. */
    private static String claimBody(String dispatchClass, String dispatchMethod,
                                    Map<String, Object> input, long fence) {
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

    /** A claim carrying the FIXED, valid dispatch coordinate the Agent builder emits. */
    private static String validClaim(Map<String, Object> input, long fence) {
        return claimBody(ToolDispatcher.DISPATCH_CLASS, ToolDispatcher.DISPATCH_METHOD, input, fence);
    }

    private static boolean waitFor(BooleanSupplier cond, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cond.getAsBoolean();
            }
        }
        return cond.getAsBoolean();
    }

    // -- tests ------------------------------------------------------------------

    @Test
    @Timeout(15)
    void happyPathDispatchesToolAndCompletesWithTheFence() {
        Map<String, Object> input = Map.of(
                "last_model_output", "searching",
                "last_model_tool_calls", List.of(toolCall("tc1", "web_search", Map.of("query", "jamjet"))));

        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson(validClaim(input, 7))));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/heartbeat")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/fail")).willReturn(ok()));

        try (var client = new JamjetEngineClient(wm.baseUrl());
             var worker = new JavaToolWorker(client, "w1",
                     ToolRegistry.of(new TestTools.WebSearchTool()),
                     Duration.ofSeconds(2), Duration.ofMillis(10))) {

            JavaToolWorker.ItemResult result = worker.runOnce();
            assertThat(result).isEqualTo(JavaToolWorker.ItemResult.COMPLETED);
        }

        // The completion MUST echo the claim's lease fence (the fenced completion path)
        // and carry the dispatcher's {messages:[assistant, tool]} as output + state_patch.
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/complete"))
                .withRequestBody(matchingJsonPath("$.lease_fence", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.execution_id", equalTo("ex_1")))
                .withRequestBody(matchingJsonPath("$.node_id", equalTo("__tools_0__")))
                .withRequestBody(matchingJsonPath("$.output.messages[0].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.output.messages[1].role", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.output.messages[1].content", equalTo("results for jamjet")))
                .withRequestBody(matchingJsonPath("$.state_patch.messages[1].content", equalTo("results for jamjet"))));
        // No failure on the happy path.
        wm.verify(0, postRequestedFor(urlEqualTo("/work-items/wi_1/fail")));
    }

    @Test
    @Timeout(15)
    void argsCoerceToTypedParametersEndToEnd() {
        Map<String, Object> input = Map.of(
                "last_model_tool_calls", List.of(toolCall("tc1", "add_numbers", Map.of("a", 2, "b", 3))));

        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson(validClaim(input, 5))));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/heartbeat")).willReturn(ok()));

        try (var client = new JamjetEngineClient(wm.baseUrl());
             var worker = new JavaToolWorker(client, "w1",
                     ToolRegistry.of(new TestTools.MathTool()),
                     Duration.ofSeconds(2), Duration.ofMillis(10))) {
            assertThat(worker.runOnce()).isEqualTo(JavaToolWorker.ItemResult.COMPLETED);
        }

        // add_numbers(int,int) with JSON {a:2,b:3} -> "5".
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/complete"))
                .withRequestBody(matchingJsonPath("$.lease_fence", equalTo("5")))
                .withRequestBody(matchingJsonPath("$.output.messages[1].content", equalTo("5"))));
    }

    @Test
    @Timeout(15)
    void fenceLostMidFlightAbortsAndNeverCompletes_M3() {
        Map<String, Object> input = Map.of(
                "last_model_tool_calls", List.of(toolCall("tc1", "slow_tool", Map.of("ignored", "x"))));

        // Heartbeat is rejected: the engine surfaces a stale fence on renew as HTTP 500
        // (FenceLost -> Internal). The worker must abort the in-flight tool and NOT complete.
        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson(validClaim(input, 7))));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/heartbeat"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"FenceLost(wi_1)\"}")));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/fail")).willReturn(ok()));

        SlowTool slow = new SlowTool();
        try (var client = new JamjetEngineClient(wm.baseUrl());
             var worker = new JavaToolWorker(client, "w1",
                     ToolRegistry.of(slow),
                     Duration.ofMillis(20), Duration.ofMillis(10))) {

            JavaToolWorker.ItemResult result = worker.runOnce();

            // Lost lease is a no-op, NOT a completion and NOT a failure.
            assertThat(result).isEqualTo(JavaToolWorker.ItemResult.LOST_LEASE);
        }

        // The in-flight tool was actually signalled to abort (M3).
        assertThat(waitFor(() -> slow.interrupted, 2_000)).as("slow tool was interrupted").isTrue();
        // A heartbeat fired (and was rejected).
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/heartbeat")));
        // CRITICAL: the reclaimed item was NEVER completed and NEVER failed by this worker.
        wm.verify(0, postRequestedFor(urlEqualTo("/work-items/wi_1/complete")));
        wm.verify(0, postRequestedFor(urlEqualTo("/work-items/wi_1/fail")));
    }

    @Test
    @Timeout(15)
    void conflictOnCompleteIsLostLeaseNotFailure() {
        Map<String, Object> input = Map.of(
                "last_model_tool_calls", List.of(toolCall("tc1", "web_search", Map.of("query", "x"))));

        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson(validClaim(input, 9))));
        // The completion is fence-rejected: a reclaimed lease yields HTTP 409.
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete"))
                .willReturn(aResponse().withStatus(409).withBody("{\"reason\":\"stale or invalid lease fence\"}")));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/heartbeat")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/fail")).willReturn(ok()));

        try (var client = new JamjetEngineClient(wm.baseUrl());
             var worker = new JavaToolWorker(client, "w1",
                     ToolRegistry.of(new TestTools.WebSearchTool()),
                     Duration.ofSeconds(2), Duration.ofMillis(10))) {
            assertThat(worker.runOnce()).isEqualTo(JavaToolWorker.ItemResult.LOST_LEASE);
        }

        // It attempted the (fenced) complete...
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/complete"))
                .withRequestBody(matchingJsonPath("$.lease_fence", equalTo("9"))));
        // ...but a 409 must NOT escalate to a fail (that would clobber the new claimant).
        wm.verify(0, postRequestedFor(urlEqualTo("/work-items/wi_1/fail")));
    }

    @Test
    @Timeout(15)
    void unknownDispatchCoordinateFailsCleanlyAndIsNotRce() {
        // A FORGED java_fn payload names an arbitrary class/method. The worker must
        // fail it cleanly and NEVER Class.forName / invoke it.
        Map<String, Object> input = Map.of("last_model_tool_calls", List.of());
        String forged = claimBody("java.lang.Runtime", "exec", input, 3);

        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson(forged)));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/fail")).willReturn(ok()));
        wm.stubFor(post(urlEqualTo("/work-items/wi_1/complete")).willReturn(ok()));

        try (var client = new JamjetEngineClient(wm.baseUrl());
             var worker = new JavaToolWorker(client, "w1",
                     ToolRegistry.of(new TestTools.WebSearchTool()),
                     Duration.ofSeconds(2), Duration.ofMillis(10))) {
            assertThat(worker.runOnce()).isEqualTo(JavaToolWorker.ItemResult.FAILED);
        }

        // Failed with a clear message naming the rejected coordinate; the arbitrary
        // class was NEVER invoked or completed.
        wm.verify(postRequestedFor(urlEqualTo("/work-items/wi_1/fail"))
                .withRequestBody(matchingJsonPath("$.error", containing("unsupported java_fn dispatch coordinate"))));
        wm.verify(0, postRequestedFor(urlEqualTo("/work-items/wi_1/complete")));
    }

    @Test
    @Timeout(15)
    void emptyQueueReturnsEmpty() {
        wm.stubFor(post(urlEqualTo("/work-items/claim")).willReturn(okJson("{\"claimed\":false}")));
        try (var client = new JamjetEngineClient(wm.baseUrl());
             var worker = new JavaToolWorker(client, "w1",
                     ToolRegistry.of(new TestTools.WebSearchTool()),
                     Duration.ofSeconds(2), Duration.ofMillis(10))) {
            assertThat(worker.runOnce()).isEqualTo(JavaToolWorker.ItemResult.EMPTY);
        }
    }
}

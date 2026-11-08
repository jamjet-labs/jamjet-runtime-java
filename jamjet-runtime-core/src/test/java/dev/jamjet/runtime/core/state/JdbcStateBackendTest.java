package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.QueueType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStateBackendTest {

    private static HikariDataSource dataSource;
    private static JdbcStateBackend backend;
    private static final ObjectMapper mapper = JamjetJson.shared();

    @BeforeAll
    static void initDb() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
        backend = new JdbcStateBackend(dataSource, mapper);
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM events");
            stmt.execute("DELETE FROM executions");
            stmt.execute("DELETE FROM workflows");
            stmt.execute("DELETE FROM snapshots");
            stmt.execute("DELETE FROM work_items");
            stmt.execute("DELETE FROM api_tokens");
            stmt.execute("DELETE FROM tenants");
        }
    }

    @Test
    void workflowRoundTrip() throws StateBackendException {
        ObjectNode ir = mapper.createObjectNode();
        ir.put("entry", "start");
        ir.putArray("nodes").addObject().put("id", "a").put("kind", "llm");

        WorkflowDefinition def = new WorkflowDefinition(
                "summarize", "1.0", ir, Instant.now()
        );

        backend.storeWorkflow(def);

        Optional<WorkflowDefinition> fetched = backend.getWorkflow("summarize", "1.0");
        assertThat(fetched).isPresent();
        assertThat(fetched.get().workflowId()).isEqualTo("summarize");
        assertThat(fetched.get().version()).isEqualTo("1.0");
        assertThat(fetched.get().ir().get("entry").asText()).isEqualTo("start");
        assertThat(fetched.get().tenantId()).isEqualTo("default");

        // Unknown version returns empty
        assertThat(backend.getWorkflow("summarize", "2.0")).isEmpty();
    }

    @Test
    void executionLifecycle() throws StateBackendException {
        ExecutionId id = ExecutionId.create();
        ObjectNode input = mapper.createObjectNode().put("prompt", "hello");
        ObjectNode state = mapper.createObjectNode();

        WorkflowExecution exec = new WorkflowExecution(
                id, "pipeline", "1.0", WorkflowStatus.PENDING,
                input, state, Instant.now(), Instant.now(), null, null
        );
        backend.createExecution(exec);

        // Verify initial state
        WorkflowExecution fetched = backend.getExecution(id).orElseThrow();
        assertThat(fetched.status()).isEqualTo(WorkflowStatus.PENDING);
        assertThat(fetched.workflowId()).isEqualTo("pipeline");

        // Update to RUNNING
        backend.updateExecutionStatus(id, WorkflowStatus.RUNNING);
        WorkflowExecution running = backend.getExecution(id).orElseThrow();
        assertThat(running.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(running.completedAt()).isNull();
    }

    @Test
    void eventAppendAndQuery() throws StateBackendException {
        ExecutionId execId = ExecutionId.create();
        ObjectNode input = mapper.createObjectNode().put("text", "test");

        Event e1 = Event.create(execId, 1,
                new EventKind.WorkflowStarted("summarize", "1.0", input));
        Event e2 = Event.create(execId, 2,
                new EventKind.NodeScheduled("llm-node", QueueType.MODEL));

        backend.appendEvent(e1);
        backend.appendEvent(e2);

        // All events
        List<Event> all = backend.getEvents(execId);
        assertThat(all).hasSize(2);

        // Events since sequence 1
        List<Event> since1 = backend.getEventsSince(execId, 1);
        assertThat(since1).hasSize(1);
        assertThat(since1.get(0).sequence()).isEqualTo(2);

        // Latest sequence
        long latest = backend.latestSequence(execId);
        assertThat(latest).isEqualTo(2);
    }

    @Test
    void workItemClaimAndComplete() throws StateBackendException {
        ExecutionId execId = ExecutionId.create();
        ObjectNode payload = mapper.createObjectNode().put("model", "gpt-4");

        WorkItem item = new WorkItem(
                UUID.randomUUID(), execId, "llm-node", "model",
                payload, 0, 3, Instant.now(), null, null
        );

        UUID itemId = backend.enqueueWorkItem(item);
        assertThat(itemId).isEqualTo(item.id());

        // Worker-1 claims
        Optional<WorkItem> claimed = backend.claimWorkItem("w1", List.of("model"));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().workerId()).isEqualTo("w1");
        assertThat(claimed.get().leaseExpiresAt()).isNotNull();

        // No more pending items for worker-2
        Optional<WorkItem> noClaim = backend.claimWorkItem("w2", List.of("model"));
        assertThat(noClaim).isEmpty();

        // Complete
        backend.completeWorkItem(itemId);

        // No more items at all
        assertThat(backend.claimWorkItem("w1", List.of("model"))).isEmpty();
    }

    @Test
    void aClaimCanSkipExecutionsAndAReleaseChargesNoAttempt() throws Exception {
        var skipped = dev.jamjet.runtime.core.ExecutionId.create();
        var other = dev.jamjet.runtime.core.ExecutionId.create();
        java.time.Instant t0 = java.time.Instant.now().minusSeconds(10);
        backend.enqueueWorkItem(new WorkItem(java.util.UUID.randomUUID(), skipped, "a", "tool",
                mapper.createObjectNode(), 1, 3, t0, null, null));
        backend.enqueueWorkItem(new WorkItem(java.util.UUID.randomUUID(), other, "b", "tool",
                mapper.createObjectNode(), 1, 3, t0.plusSeconds(1), null, null));

        var first = backend.claimWorkItem("w1", java.util.List.of("tool"), java.util.Set.of(skipped));
        org.assertj.core.api.Assertions.assertThat(first).get().extracting(WorkItem::executionId).isEqualTo(other);

        var claimed = backend.claimWorkItem("w1", java.util.List.of("tool"));
        org.assertj.core.api.Assertions.assertThat(claimed).get().extracting(WorkItem::executionId).isEqualTo(skipped);
        backend.releaseWorkItem(claimed.get().id(), "w1");
        var again = backend.claimWorkItem("w2", java.util.List.of("tool"));
        org.assertj.core.api.Assertions.assertThat(again).get().satisfies(i -> {
            org.assertj.core.api.Assertions.assertThat(i.executionId()).isEqualTo(skipped);
            org.assertj.core.api.Assertions.assertThat(i.attempt()).isEqualTo(1);
        });
    }
}

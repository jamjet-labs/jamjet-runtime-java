package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fully in-memory {@link StateBackend} implementation.
 * Thread-safe via ConcurrentHashMap and targeted synchronization.
 * Suitable for testing and single-process use.
 */
public class InMemoryStateBackend implements StateBackend {

    private static final long LEASE_DURATION_SECONDS = 30;

    // Workflows: key = workflowId:version
    private final ConcurrentHashMap<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();

    // Executions
    private final ConcurrentHashMap<ExecutionId, WorkflowExecution> executions = new ConcurrentHashMap<>();

    // Events: per-execution ordered list
    private final ConcurrentHashMap<ExecutionId, List<Event>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExecutionId, AtomicLong> sequenceTrackers = new ConcurrentHashMap<>();

    // Snapshots: latest per execution
    private final ConcurrentHashMap<ExecutionId, Snapshot> snapshots = new ConcurrentHashMap<>();

    // Work items
    private final ConcurrentHashMap<UUID, WorkItem> workItems = new ConcurrentHashMap<>();

    // Tokens: plaintext -> ApiToken
    private final ConcurrentHashMap<String, ApiToken> tokens = new ConcurrentHashMap<>();

    // Tenants
    private final ConcurrentHashMap<String, Tenant> tenants = new ConcurrentHashMap<>();

    // ── Workflows ──────────────────────────────────────────────────────

    @Override
    public void storeWorkflow(WorkflowDefinition def) throws StateBackendException {
        workflows.put(workflowKey(def.workflowId(), def.version()), def);
    }

    @Override
    public Optional<WorkflowDefinition> getWorkflow(String workflowId, String version) throws StateBackendException {
        return Optional.ofNullable(workflows.get(workflowKey(workflowId, version)));
    }

    // ── Executions ─────────────────────────────────────────────────────

    @Override
    public void createExecution(WorkflowExecution execution) throws StateBackendException {
        executions.put(execution.executionId(), execution);
    }

    @Override
    public Optional<WorkflowExecution> getExecution(ExecutionId id) throws StateBackendException {
        return Optional.ofNullable(executions.get(id));
    }

    @Override
    public void updateExecutionStatus(ExecutionId id, WorkflowStatus status) throws StateBackendException {
        WorkflowExecution existing = executions.get(id);
        if (existing == null) {
            throw StateBackendException.notFound(id.toString());
        }
        Instant now = Instant.now();
        Instant completedAt = status.isTerminal() ? now : existing.completedAt();
        WorkflowExecution updated = new WorkflowExecution(
                existing.executionId(),
                existing.workflowId(),
                existing.workflowVersion(),
                status,
                existing.initialInput(),
                existing.currentState(),
                existing.startedAt(),
                now,
                completedAt,
                existing.sessionType()
        );
        executions.put(id, updated);
    }

    @Override
    public void updateExecutionCurrentState(ExecutionId id, JsonNode currentState) throws StateBackendException {
        WorkflowExecution existing = executions.get(id);
        if (existing == null) {
            throw StateBackendException.notFound(id.toString());
        }
        WorkflowExecution updated = new WorkflowExecution(
                existing.executionId(),
                existing.workflowId(),
                existing.workflowVersion(),
                existing.status(),
                existing.initialInput(),
                currentState,
                existing.startedAt(),
                Instant.now(),
                existing.completedAt(),
                existing.sessionType()
        );
        executions.put(id, updated);
    }

    @Override
    public void patchAppendArray(ExecutionId executionId, String key, JsonNode value) throws StateBackendException {
        WorkflowExecution existing = executions.get(executionId);
        if (existing == null) {
            throw StateBackendException.notFound(executionId.toString());
        }

        ObjectNode state;
        if (existing.currentState() != null && existing.currentState().isObject()) {
            state = (ObjectNode) existing.currentState().deepCopy();
        } else {
            state = JamjetJson.shared().createObjectNode();
        }

        ArrayNode array;
        if (state.has(key) && state.get(key).isArray()) {
            array = (ArrayNode) state.get(key);
        } else {
            array = state.putArray(key);
        }
        array.add(value);

        updateExecutionCurrentState(executionId, state);
    }

    @Override
    public List<WorkflowExecution> listExecutions(WorkflowStatus status, int limit, int offset) throws StateBackendException {
        return executions.values().stream()
                .filter(e -> status == null || e.status() == status)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    // ── Events ─────────────────────────────────────────────────────────

    @Override
    public synchronized long appendEvent(Event event) throws StateBackendException {
        ExecutionId execId = event.executionId();
        List<Event> eventList = events.computeIfAbsent(execId, k -> new ArrayList<>());
        AtomicLong tracker = sequenceTrackers.computeIfAbsent(execId, k -> new AtomicLong(0));
        tracker.updateAndGet(current -> Math.max(current, event.sequence()));
        eventList.add(event);
        return event.sequence();
    }

    @Override
    public List<Event> getEvents(ExecutionId executionId) throws StateBackendException {
        List<Event> eventList = events.get(executionId);
        return eventList == null ? List.of() : List.copyOf(eventList);
    }

    @Override
    public List<Event> getEventsSince(ExecutionId executionId, long sinceSequence) throws StateBackendException {
        List<Event> eventList = events.get(executionId);
        if (eventList == null) {
            return List.of();
        }
        return eventList.stream()
                .filter(e -> e.sequence() > sinceSequence)
                .toList();
    }

    @Override
    public long latestSequence(ExecutionId executionId) throws StateBackendException {
        AtomicLong tracker = sequenceTrackers.get(executionId);
        return tracker == null ? 0 : tracker.get();
    }

    // ── Snapshots ──────────────────────────────────────────────────────

    @Override
    public void writeSnapshot(Snapshot snapshot) throws StateBackendException {
        snapshots.put(snapshot.executionId(), snapshot);
    }

    @Override
    public Optional<Snapshot> latestSnapshot(ExecutionId executionId) throws StateBackendException {
        return Optional.ofNullable(snapshots.get(executionId));
    }

    // ── Work queue ─────────────────────────────────────────────────────

    @Override
    public UUID enqueueWorkItem(WorkItem item) throws StateBackendException {
        workItems.put(item.id(), item);
        return item.id();
    }

    @Override
    public synchronized Optional<WorkItem> claimWorkItem(String workerId, List<String> queueTypes) throws StateBackendException {
        for (WorkItem item : workItems.values()) {
            if (item.workerId() == null && queueTypes.contains(item.queueType())) {
                Instant leaseExpires = Instant.now().plusSeconds(LEASE_DURATION_SECONDS);
                WorkItem claimed = new WorkItem(
                        item.id(), item.executionId(), item.nodeId(), item.queueType(),
                        item.payload(), item.attempt(), item.maxAttempts(),
                        item.createdAt(), leaseExpires, workerId, item.tenantId()
                );
                workItems.put(item.id(), claimed);
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    @Override
    public void renewLease(UUID itemId, String workerId) throws StateBackendException {
        WorkItem item = workItems.get(itemId);
        if (item == null) {
            throw StateBackendException.notFound(itemId.toString());
        }
        if (!workerId.equals(item.workerId())) {
            throw StateBackendException.database("Worker " + workerId + " does not hold lease on " + itemId);
        }
        Instant leaseExpires = Instant.now().plusSeconds(LEASE_DURATION_SECONDS);
        WorkItem renewed = new WorkItem(
                item.id(), item.executionId(), item.nodeId(), item.queueType(),
                item.payload(), item.attempt(), item.maxAttempts(),
                item.createdAt(), leaseExpires, workerId, item.tenantId()
        );
        workItems.put(itemId, renewed);
    }

    @Override
    public void completeWorkItem(UUID itemId) throws StateBackendException {
        if (workItems.remove(itemId) == null) {
            throw StateBackendException.notFound(itemId.toString());
        }
    }

    @Override
    public void failWorkItem(UUID itemId, String error) throws StateBackendException {
        WorkItem item = workItems.get(itemId);
        if (item == null) {
            throw StateBackendException.notFound(itemId.toString());
        }
        // Reset to unclaimed for retry, increment attempt
        WorkItem failed = new WorkItem(
                item.id(), item.executionId(), item.nodeId(), item.queueType(),
                item.payload(), item.attempt() + 1, item.maxAttempts(),
                item.createdAt(), null, null, item.tenantId()
        );
        workItems.put(itemId, failed);
    }

    @Override
    public ReclaimResult reclaimExpiredLeases() throws StateBackendException {
        Instant now = Instant.now();
        List<WorkItem> retryable = new ArrayList<>();
        List<WorkItem> exhausted = new ArrayList<>();

        for (WorkItem item : workItems.values()) {
            if (item.leaseExpiresAt() != null && item.leaseExpiresAt().isBefore(now)) {
                if (item.attempt() < item.maxAttempts()) {
                    // Reset to unclaimed with incremented attempt
                    WorkItem reset = new WorkItem(
                            item.id(), item.executionId(), item.nodeId(), item.queueType(),
                            item.payload(), item.attempt() + 1, item.maxAttempts(),
                            item.createdAt(), null, null, item.tenantId()
                    );
                    workItems.put(item.id(), reset);
                    retryable.add(reset);
                } else {
                    // Exhausted — remove from active queue
                    workItems.remove(item.id());
                    exhausted.add(item);
                }
            }
        }

        return new ReclaimResult(retryable, exhausted);
    }

    @Override
    public void moveToDeadLetter(UUID itemId, String lastError) throws StateBackendException {
        if (workItems.remove(itemId) == null) {
            throw StateBackendException.notFound(itemId.toString());
        }
        // In-memory backend just discards; a real backend would persist to a dead-letter table.
    }

    // ── Tokens ─────────────────────────────────────────────────────────

    @Override
    public TokenPair createToken(String name, String role) throws StateBackendException {
        String plaintext = "jrt_" + UUID.randomUUID().toString().replace("-", "");
        String id = UUID.randomUUID().toString();
        ApiToken token = new ApiToken(id, name, role, Instant.now());
        tokens.put(plaintext, token);
        return new TokenPair(plaintext, token);
    }

    @Override
    public Optional<ApiToken> validateToken(String token) throws StateBackendException {
        return Optional.ofNullable(tokens.get(token));
    }

    // ── Tenants ────────────────────────────────────────────────────────

    @Override
    public void createTenant(Tenant tenant) throws StateBackendException {
        tenants.put(tenant.id(), tenant);
    }

    @Override
    public Optional<Tenant> getTenant(String id) throws StateBackendException {
        return Optional.ofNullable(tenants.get(id));
    }

    @Override
    public List<Tenant> listTenants() throws StateBackendException {
        return List.copyOf(tenants.values());
    }

    @Override
    public void updateTenant(Tenant tenant) throws StateBackendException {
        if (!tenants.containsKey(tenant.id())) {
            throw StateBackendException.notFound(tenant.id());
        }
        tenants.put(tenant.id(), tenant);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static String workflowKey(String workflowId, String version) {
        return workflowId + ":" + version;
    }
}

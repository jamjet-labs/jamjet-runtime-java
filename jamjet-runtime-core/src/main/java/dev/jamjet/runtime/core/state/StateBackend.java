package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage abstraction for workflow state, events, snapshots, work items, tokens, and tenants.
 * All methods throw {@link StateBackendException} on storage failures.
 */
public interface StateBackend {

    // ── Workflows ──────────────────────────────────────────────────────

    void storeWorkflow(WorkflowDefinition def) throws StateBackendException;

    Optional<WorkflowDefinition> getWorkflow(String workflowId, String version) throws StateBackendException;

    // ── Executions ─────────────────────────────────────────────────────

    void createExecution(WorkflowExecution execution) throws StateBackendException;

    Optional<WorkflowExecution> getExecution(ExecutionId id) throws StateBackendException;

    void updateExecutionStatus(ExecutionId id, WorkflowStatus status) throws StateBackendException;

    void updateExecutionCurrentState(ExecutionId id, JsonNode currentState) throws StateBackendException;

    void patchAppendArray(ExecutionId executionId, String key, JsonNode value) throws StateBackendException;

    /**
     * List executions, optionally filtered by status.
     *
     * @param status filter by this status, or null for all statuses
     * @param limit  maximum number of results
     * @param offset number of results to skip
     */
    List<WorkflowExecution> listExecutions(WorkflowStatus status, int limit, int offset) throws StateBackendException;

    // ── Events (append-only log) ───────────────────────────────────────

    long appendEvent(Event event) throws StateBackendException;

    List<Event> getEvents(ExecutionId executionId) throws StateBackendException;

    List<Event> getEventsSince(ExecutionId executionId, long sinceSequence) throws StateBackendException;

    long latestSequence(ExecutionId executionId) throws StateBackendException;

    // ── Snapshots ──────────────────────────────────────────────────────

    void writeSnapshot(Snapshot snapshot) throws StateBackendException;

    Optional<Snapshot> latestSnapshot(ExecutionId executionId) throws StateBackendException;

    // ── Work queue (lease-based) ───────────────────────────────────────

    UUID enqueueWorkItem(WorkItem item) throws StateBackendException;

    Optional<WorkItem> claimWorkItem(String workerId, List<String> queueTypes) throws StateBackendException;

    void renewLease(UUID itemId, String workerId) throws StateBackendException;

    void completeWorkItem(UUID itemId) throws StateBackendException;

    void failWorkItem(UUID itemId, String error) throws StateBackendException;

    ReclaimResult reclaimExpiredLeases() throws StateBackendException;

    void moveToDeadLetter(UUID itemId, String lastError) throws StateBackendException;

    // ── API tokens ─────────────────────────────────────────────────────

    record TokenPair(String plaintextToken, ApiToken info) {}

    TokenPair createToken(String name, String role) throws StateBackendException;

    Optional<ApiToken> validateToken(String token) throws StateBackendException;

    // ── Tenants ────────────────────────────────────────────────────────

    default void createTenant(Tenant tenant) throws StateBackendException {
        throw new StateBackendException(StateBackendException.Kind.DATABASE, "Tenant management not supported");
    }

    default Optional<Tenant> getTenant(String id) throws StateBackendException {
        return Optional.empty();
    }

    default List<Tenant> listTenants() throws StateBackendException {
        return List.of();
    }

    default void updateTenant(Tenant tenant) throws StateBackendException {
        throw new StateBackendException(StateBackendException.Kind.DATABASE, "Tenant management not supported");
    }
}

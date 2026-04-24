package dev.jamjet.runtime.core.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.event.EventKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-backed {@link StateBackend} implementation.
 * Compatible with H2 (testing) and PostgreSQL (production).
 * Uses Flyway migrations for schema management.
 */
public class JdbcStateBackend implements StateBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcStateBackend.class);

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcStateBackend(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    // ── Workflows ──────────────────────────────────────────────────────

    @Override
    public void storeWorkflow(WorkflowDefinition def) throws StateBackendException {
        String sql = """
                MERGE INTO workflows (workflow_id, version, ir, tenant_id, created_at)
                KEY (workflow_id, version)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, def.workflowId());
            ps.setString(2, def.version());
            ps.setString(3, writeJson(def.ir()));
            ps.setString(4, def.tenantId());
            ps.setTimestamp(5, Timestamp.from(def.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to store workflow", e);
        }
    }

    @Override
    public Optional<WorkflowDefinition> getWorkflow(String workflowId, String version) throws StateBackendException {
        String sql = "SELECT * FROM workflows WHERE workflow_id = ? AND version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new WorkflowDefinition(
                            rs.getString("workflow_id"),
                            rs.getString("version"),
                            readJson(rs.getString("ir")),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("tenant_id")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get workflow", e);
        }
    }

    // ── Executions ─────────────────────────────────────────────────────

    @Override
    public void createExecution(WorkflowExecution execution) throws StateBackendException {
        String sql = """
                INSERT INTO executions (execution_id, workflow_id, workflow_version, status,
                    initial_input, current_state, session_type, started_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, execution.executionId().value().toString());
            ps.setString(2, execution.workflowId());
            ps.setString(3, execution.workflowVersion());
            ps.setString(4, execution.status().getValue());
            ps.setString(5, writeJson(execution.initialInput()));
            ps.setString(6, writeJson(execution.currentState()));
            ps.setString(7, execution.sessionType() != null ? execution.sessionType().getValue() : null);
            ps.setTimestamp(8, Timestamp.from(execution.startedAt()));
            ps.setTimestamp(9, Timestamp.from(execution.updatedAt()));
            ps.setTimestamp(10, execution.completedAt() != null ? Timestamp.from(execution.completedAt()) : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to create execution", e);
        }
    }

    @Override
    public Optional<WorkflowExecution> getExecution(ExecutionId id) throws StateBackendException {
        String sql = "SELECT * FROM executions WHERE execution_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readExecution(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get execution", e);
        }
    }

    @Override
    public void updateExecutionStatus(ExecutionId id, WorkflowStatus status) throws StateBackendException {
        Instant now = Instant.now();
        boolean terminal = status.isTerminal();

        String sql = terminal
                ? "UPDATE executions SET status = ?, updated_at = ?, completed_at = ? WHERE execution_id = ?"
                : "UPDATE executions SET status = ?, updated_at = ? WHERE execution_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.getValue());
            ps.setTimestamp(2, Timestamp.from(now));
            if (terminal) {
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setString(4, id.value().toString());
            } else {
                ps.setString(3, id.value().toString());
            }
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(id.toString());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to update execution status", e);
        }
    }

    @Override
    public void updateExecutionCurrentState(ExecutionId id, JsonNode currentState) throws StateBackendException {
        String sql = "UPDATE executions SET current_state = ?, updated_at = ? WHERE execution_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, writeJson(currentState));
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id.value().toString());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(id.toString());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to update execution state", e);
        }
    }

    @Override
    public void patchAppendArray(ExecutionId executionId, String key, JsonNode value) throws StateBackendException {
        // Load current state, patch in memory, write back
        WorkflowExecution exec = getExecution(executionId)
                .orElseThrow(() -> StateBackendException.notFound(executionId.toString()));

        com.fasterxml.jackson.databind.node.ObjectNode state;
        if (exec.currentState() != null && exec.currentState().isObject()) {
            state = (com.fasterxml.jackson.databind.node.ObjectNode) exec.currentState().deepCopy();
        } else {
            state = mapper.createObjectNode();
        }

        com.fasterxml.jackson.databind.node.ArrayNode array;
        if (state.has(key) && state.get(key).isArray()) {
            array = (com.fasterxml.jackson.databind.node.ArrayNode) state.get(key);
        } else {
            array = state.putArray(key);
        }
        array.add(value);

        updateExecutionCurrentState(executionId, state);
    }

    @Override
    public List<WorkflowExecution> listExecutions(WorkflowStatus status, int limit, int offset) throws StateBackendException {
        String sql;
        if (status != null) {
            sql = "SELECT * FROM executions WHERE status = ? ORDER BY started_at DESC LIMIT ? OFFSET ?";
        } else {
            sql = "SELECT * FROM executions ORDER BY started_at DESC LIMIT ? OFFSET ?";
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int paramIdx = 1;
            if (status != null) {
                ps.setString(paramIdx++, status.getValue());
            }
            ps.setInt(paramIdx++, limit);
            ps.setInt(paramIdx, offset);

            try (ResultSet rs = ps.executeQuery()) {
                List<WorkflowExecution> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(readExecution(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to list executions", e);
        }
    }

    // ── Events ─────────────────────────────────────────────────────────

    @Override
    public long appendEvent(Event event) throws StateBackendException {
        String sql = "INSERT INTO events (id, execution_id, sequence, kind, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.id().toString());
            ps.setString(2, event.executionId().value().toString());
            ps.setLong(3, event.sequence());
            ps.setString(4, writeValue(event.kind()));
            ps.setTimestamp(5, Timestamp.from(event.createdAt()));
            ps.executeUpdate();
            return event.sequence();
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to append event", e);
        }
    }

    @Override
    public List<Event> getEvents(ExecutionId executionId) throws StateBackendException {
        String sql = "SELECT * FROM events WHERE execution_id = ? ORDER BY sequence";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return readEvents(rs);
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get events", e);
        }
    }

    @Override
    public List<Event> getEventsSince(ExecutionId executionId, long sinceSequence) throws StateBackendException {
        String sql = "SELECT * FROM events WHERE execution_id = ? AND sequence > ? ORDER BY sequence";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId.value().toString());
            ps.setLong(2, sinceSequence);
            try (ResultSet rs = ps.executeQuery()) {
                return readEvents(rs);
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get events since sequence", e);
        }
    }

    @Override
    public long latestSequence(ExecutionId executionId) throws StateBackendException {
        String sql = "SELECT COALESCE(MAX(sequence), 0) FROM events WHERE execution_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get latest sequence", e);
        }
    }

    // ── Snapshots ──────────────────────────────────────────────────────

    @Override
    public void writeSnapshot(Snapshot snapshot) throws StateBackendException {
        String sql = "INSERT INTO snapshots (id, execution_id, at_sequence, state, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshot.id().toString());
            ps.setString(2, snapshot.executionId().value().toString());
            ps.setLong(3, snapshot.atSequence());
            ps.setString(4, writeJson(snapshot.state()));
            ps.setTimestamp(5, Timestamp.from(snapshot.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to write snapshot", e);
        }
    }

    @Override
    public Optional<Snapshot> latestSnapshot(ExecutionId executionId) throws StateBackendException {
        String sql = "SELECT * FROM snapshots WHERE execution_id = ? ORDER BY at_sequence DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, executionId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Snapshot(
                            UUID.fromString(rs.getString("id")),
                            ExecutionId.of(UUID.fromString(rs.getString("execution_id"))),
                            rs.getLong("at_sequence"),
                            readJson(rs.getString("state")),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get latest snapshot", e);
        }
    }

    // ── Work queue ─────────────────────────────────────────────────────

    @Override
    public UUID enqueueWorkItem(WorkItem item) throws StateBackendException {
        String sql = """
                INSERT INTO work_items (id, execution_id, node_id, queue_type, payload,
                    attempt, max_attempts, status, tenant_id, created_at, lease_expires_at, worker_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.id().toString());
            ps.setString(2, item.executionId().value().toString());
            ps.setString(3, item.nodeId());
            ps.setString(4, item.queueType());
            ps.setString(5, writeJson(item.payload()));
            ps.setInt(6, item.attempt());
            ps.setInt(7, item.maxAttempts());
            ps.setString(8, item.tenantId());
            ps.setTimestamp(9, Timestamp.from(item.createdAt()));
            ps.setTimestamp(10, item.leaseExpiresAt() != null ? Timestamp.from(item.leaseExpiresAt()) : null);
            ps.setString(11, item.workerId());
            ps.executeUpdate();
            return item.id();
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to enqueue work item", e);
        }
    }

    @Override
    public Optional<WorkItem> claimWorkItem(String workerId, List<String> queueTypes) throws StateBackendException {
        if (queueTypes.isEmpty()) {
            return Optional.empty();
        }

        // Build IN clause placeholders
        String placeholders = String.join(",", queueTypes.stream().map(q -> "?").toList());
        String selectSql = "SELECT * FROM work_items WHERE status = 'pending' AND queue_type IN ("
                + placeholders + ") ORDER BY created_at LIMIT 1 FOR UPDATE";
        String updateSql = "UPDATE work_items SET status = 'claimed', worker_id = ?, lease_expires_at = ? WHERE id = ?";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                WorkItem item = null;
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    int idx = 1;
                    for (String qt : queueTypes) {
                        ps.setString(idx++, qt);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            item = readWorkItem(rs);
                        }
                    }
                }

                if (item == null) {
                    conn.commit();
                    return Optional.empty();
                }

                Instant leaseExpires = Instant.now().plusSeconds(30);
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, workerId);
                    ps.setTimestamp(2, Timestamp.from(leaseExpires));
                    ps.setString(3, item.id().toString());
                    ps.executeUpdate();
                }

                conn.commit();

                // Return the claimed item with updated fields
                WorkItem claimed = new WorkItem(
                        item.id(), item.executionId(), item.nodeId(), item.queueType(),
                        item.payload(), item.attempt(), item.maxAttempts(),
                        item.createdAt(), leaseExpires, workerId, item.tenantId()
                );
                return Optional.of(claimed);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to claim work item", e);
        }
    }

    @Override
    public void renewLease(UUID itemId, String workerId) throws StateBackendException {
        Instant leaseExpires = Instant.now().plusSeconds(30);
        String sql = "UPDATE work_items SET lease_expires_at = ? WHERE id = ? AND worker_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(leaseExpires));
            ps.setString(2, itemId.toString());
            ps.setString(3, workerId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(itemId.toString());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to renew lease", e);
        }
    }

    @Override
    public void completeWorkItem(UUID itemId) throws StateBackendException {
        String sql = "UPDATE work_items SET status = 'completed' WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId.toString());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(itemId.toString());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to complete work item", e);
        }
    }

    @Override
    public void failWorkItem(UUID itemId, String error) throws StateBackendException {
        String sql = "UPDATE work_items SET status = 'failed' WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId.toString());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(itemId.toString());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to fail work item", e);
        }
    }

    @Override
    public ReclaimResult reclaimExpiredLeases() throws StateBackendException {
        return ReclaimResult.empty();
    }

    @Override
    public void moveToDeadLetter(UUID itemId, String lastError) throws StateBackendException {
        String sql = "UPDATE work_items SET status = 'dead_letter' WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId.toString());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(itemId.toString());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to move to dead letter", e);
        }
    }

    // ── Tokens ─────────────────────────────────────────────────────────

    @Override
    public TokenPair createToken(String name, String role) throws StateBackendException {
        String plaintext = "jrt_" + UUID.randomUUID().toString().replace("-", "");
        String id = UUID.randomUUID().toString();
        String tokenHash = Integer.toHexString(plaintext.hashCode());

        String sql = "INSERT INTO api_tokens (id, name, role, token_hash, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Instant now = Instant.now();
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, role);
            ps.setString(4, tokenHash);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.executeUpdate();

            ApiToken token = new ApiToken(id, name, role, now);
            return new TokenPair(plaintext, token);
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to create token", e);
        }
    }

    @Override
    public Optional<ApiToken> validateToken(String token) throws StateBackendException {
        String tokenHash = Integer.toHexString(token.hashCode());
        String sql = "SELECT * FROM api_tokens WHERE token_hash = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp expiresAt = rs.getTimestamp("expires_at");
                    return Optional.of(new ApiToken(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("role"),
                            rs.getTimestamp("created_at").toInstant(),
                            expiresAt != null ? expiresAt.toInstant() : null,
                            rs.getString("tenant_id")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to validate token", e);
        }
    }

    // ── Tenants ────────────────────────────────────────────────────────

    @Override
    public void createTenant(Tenant tenant) throws StateBackendException {
        String sql = """
                INSERT INTO tenants (id, name, status, policy, limits, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenant.id());
            ps.setString(2, tenant.name());
            ps.setString(3, tenant.status().getValue());
            ps.setString(4, tenant.policy() != null ? writeJson(tenant.policy()) : null);
            ps.setString(5, tenant.limits() != null ? writeValue(tenant.limits()) : null);
            ps.setTimestamp(6, Timestamp.from(tenant.createdAt()));
            ps.setTimestamp(7, Timestamp.from(tenant.updatedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to create tenant", e);
        }
    }

    @Override
    public Optional<Tenant> getTenant(String id) throws StateBackendException {
        String sql = "SELECT * FROM tenants WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readTenant(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to get tenant", e);
        }
    }

    @Override
    public List<Tenant> listTenants() throws StateBackendException {
        String sql = "SELECT * FROM tenants ORDER BY created_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Tenant> results = new ArrayList<>();
            while (rs.next()) {
                results.add(readTenant(rs));
            }
            return results;
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to list tenants", e);
        }
    }

    @Override
    public void updateTenant(Tenant tenant) throws StateBackendException {
        String sql = "UPDATE tenants SET name = ?, status = ?, policy = ?, limits = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenant.name());
            ps.setString(2, tenant.status().getValue());
            ps.setString(3, tenant.policy() != null ? writeJson(tenant.policy()) : null);
            ps.setString(4, tenant.limits() != null ? writeValue(tenant.limits()) : null);
            ps.setTimestamp(5, Timestamp.from(tenant.updatedAt()));
            ps.setString(6, tenant.id());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw StateBackendException.notFound(tenant.id());
            }
        } catch (SQLException e) {
            throw StateBackendException.database("Failed to update tenant", e);
        }
    }

    // ── ResultSet mapping helpers ──────────────────────────────────────

    private WorkflowExecution readExecution(ResultSet rs) throws SQLException, StateBackendException {
        String sessionTypeStr = rs.getString("session_type");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new WorkflowExecution(
                ExecutionId.of(UUID.fromString(rs.getString("execution_id"))),
                rs.getString("workflow_id"),
                rs.getString("workflow_version"),
                WorkflowStatus.fromValue(rs.getString("status")),
                readJson(rs.getString("initial_input")),
                readJson(rs.getString("current_state")),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completedAt != null ? completedAt.toInstant() : null,
                sessionTypeStr != null ? SessionType.fromValue(sessionTypeStr) : null
        );
    }

    private List<Event> readEvents(ResultSet rs) throws SQLException, StateBackendException {
        List<Event> events = new ArrayList<>();
        while (rs.next()) {
            try {
                events.add(new Event(
                        UUID.fromString(rs.getString("id")),
                        ExecutionId.of(UUID.fromString(rs.getString("execution_id"))),
                        rs.getLong("sequence"),
                        mapper.readValue(rs.getString("kind"), EventKind.class),
                        rs.getTimestamp("created_at").toInstant()
                ));
            } catch (JsonProcessingException e) {
                throw new StateBackendException(
                        StateBackendException.Kind.SERIALIZATION,
                        "Failed to deserialize EventKind", e);
            }
        }
        return events;
    }

    private WorkItem readWorkItem(ResultSet rs) throws SQLException, StateBackendException {
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        return new WorkItem(
                UUID.fromString(rs.getString("id")),
                ExecutionId.of(UUID.fromString(rs.getString("execution_id"))),
                rs.getString("node_id"),
                rs.getString("queue_type"),
                readJson(rs.getString("payload")),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("created_at").toInstant(),
                leaseExpiresAt != null ? leaseExpiresAt.toInstant() : null,
                rs.getString("worker_id"),
                rs.getString("tenant_id")
        );
    }

    private Tenant readTenant(ResultSet rs) throws SQLException, StateBackendException {
        String limitsStr = rs.getString("limits");
        TenantLimits limits = null;
        if (limitsStr != null) {
            try {
                limits = mapper.readValue(limitsStr, TenantLimits.class);
            } catch (JsonProcessingException e) {
                throw new StateBackendException(
                        StateBackendException.Kind.SERIALIZATION,
                        "Failed to deserialize TenantLimits", e);
            }
        }
        String policyStr = rs.getString("policy");
        return new Tenant(
                rs.getString("id"),
                rs.getString("name"),
                TenantStatus.fromValue(rs.getString("status")),
                policyStr != null ? readJson(policyStr) : null,
                limits,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // ── JSON serialization helpers ─────────────────────────────────────

    private String writeJson(JsonNode node) throws StateBackendException {
        if (node == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new StateBackendException(
                    StateBackendException.Kind.SERIALIZATION,
                    "Failed to serialize JsonNode", e);
        }
    }

    private String writeValue(Object value) throws StateBackendException {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new StateBackendException(
                    StateBackendException.Kind.SERIALIZATION,
                    "Failed to serialize value", e);
        }
    }

    private JsonNode readJson(String json) throws StateBackendException {
        if (json == null) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new StateBackendException(
                    StateBackendException.Kind.SERIALIZATION,
                    "Failed to deserialize JSON", e);
        }
    }
}

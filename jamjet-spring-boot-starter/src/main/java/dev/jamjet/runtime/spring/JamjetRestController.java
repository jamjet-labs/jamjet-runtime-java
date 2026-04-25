package dev.jamjet.runtime.spring;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.event.Event;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.StateBackendException;
import dev.jamjet.runtime.core.state.WorkflowExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller exposing JamJet runtime operations under {@code /jamjet}.
 */
@RestController
@RequestMapping("/jamjet")
public class JamjetRestController {

    private final StateBackend backend;

    public JamjetRestController(StateBackend backend) {
        this.backend = backend;
    }

    /** GET /jamjet/health */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "version", "0.1.0-SNAPSHOT");
    }

    /** POST /jamjet/executions — start a new execution */
    @PostMapping("/executions")
    public ResponseEntity<?> startExecution(@RequestBody StartExecutionRequest request) {
        if (request.workflowId() == null || request.workflowId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Field 'workflow_id' is required"));
        }
        String version = request.workflowVersion() != null ? request.workflowVersion() : "1";
        ExecutionId executionId = ExecutionId.create();
        Instant now = Instant.now();
        WorkflowExecution execution = new WorkflowExecution(
                executionId,
                request.workflowId(),
                version,
                WorkflowStatus.PENDING,
                request.input(),
                null,
                now,
                now,
                null,
                SessionType.STATELESS
        );
        try {
            backend.createExecution(execution);
        } catch (StateBackendException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(execution);
    }

    /** GET /jamjet/executions — list executions with optional filtering */
    @GetMapping("/executions")
    public ResponseEntity<?> listExecutions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        WorkflowStatus workflowStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                workflowStatus = WorkflowStatus.fromValue(status);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown status: " + status));
            }
        }
        try {
            List<WorkflowExecution> executions = backend.listExecutions(workflowStatus, limit, offset);
            return ResponseEntity.ok(executions);
        } catch (StateBackendException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /jamjet/executions/{id} — get a single execution */
    @GetMapping("/executions/{id}")
    public ResponseEntity<?> getExecution(@PathVariable String id) {
        ExecutionId executionId = parseExecutionId(id);
        if (executionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid execution id: " + id));
        }
        try {
            Optional<WorkflowExecution> execution = backend.getExecution(executionId);
            if (execution.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Execution not found: " + id));
            }
            return ResponseEntity.ok(execution.get());
        } catch (StateBackendException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /jamjet/executions/{id}/cancel — cancel an execution */
    @PostMapping("/executions/{id}/cancel")
    public ResponseEntity<?> cancelExecution(@PathVariable String id) {
        ExecutionId executionId = parseExecutionId(id);
        if (executionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid execution id: " + id));
        }
        try {
            Optional<WorkflowExecution> existing = backend.getExecution(executionId);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Execution not found: " + id));
            }
            backend.updateExecutionStatus(executionId, WorkflowStatus.CANCELLED);
            WorkflowExecution updated = backend.getExecution(executionId).orElse(existing.get());
            return ResponseEntity.ok(updated);
        } catch (StateBackendException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /jamjet/executions/{id}/events — get the event log for an execution */
    @GetMapping("/executions/{id}/events")
    public ResponseEntity<?> getEvents(@PathVariable String id) {
        ExecutionId executionId = parseExecutionId(id);
        if (executionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid execution id: " + id));
        }
        try {
            Optional<WorkflowExecution> existing = backend.getExecution(executionId);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Execution not found: " + id));
            }
            List<Event> events = backend.getEvents(executionId);
            return ResponseEntity.ok(events);
        } catch (StateBackendException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static ExecutionId parseExecutionId(String raw) {
        try {
            if (raw.startsWith("exec_")) {
                String hex = raw.substring(5);
                if (hex.length() == 32) {
                    String uuid = hex.substring(0, 8) + "-"
                            + hex.substring(8, 12) + "-"
                            + hex.substring(12, 16) + "-"
                            + hex.substring(16, 20) + "-"
                            + hex.substring(20);
                    return ExecutionId.fromString(uuid);
                }
            }
            return ExecutionId.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Request body for starting a new execution.
     *
     * @param workflowId      the workflow definition id
     * @param workflowVersion the workflow version (defaults to "1" if null)
     * @param input           initial input as a JSON node
     */
    public record StartExecutionRequest(String workflowId, String workflowVersion, JsonNode input) {}
}

package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.SessionType;
import dev.jamjet.runtime.core.WorkflowStatus;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.WorkflowExecution;
import dev.jamjet.runtime.server.dto.StartExecutionRequest;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class ExecutionRoutes {

    public static void register(Javalin app, StateBackend backend) {

        // POST /executions — start a new execution
        app.post("/executions", ctx -> {
            StartExecutionRequest req = ctx.bodyAsClass(StartExecutionRequest.class);
            if (req.workflowId() == null || req.workflowId().isBlank()) {
                ctx.status(400).json(new ErrorResponse("Field 'workflow_id' is required"));
                return;
            }

            String version = req.workflowVersion() != null ? req.workflowVersion() : "1";
            ExecutionId executionId = ExecutionId.create();
            Instant now = Instant.now();

            WorkflowExecution execution = new WorkflowExecution(
                    executionId,
                    req.workflowId(),
                    version,
                    WorkflowStatus.PENDING,
                    req.input(),
                    null,
                    now,
                    now,
                    null,
                    SessionType.STATELESS
            );

            backend.createExecution(execution);

            ctx.status(201).json(execution);
        });

        // GET /executions — list with optional filtering
        app.get("/executions", ctx -> {
            String statusParam = ctx.queryParam("status");
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 100);
            int offset = parseIntOrDefault(ctx.queryParam("offset"), 0);

            WorkflowStatus status = null;
            if (statusParam != null && !statusParam.isBlank()) {
                try {
                    status = WorkflowStatus.fromValue(statusParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(new ErrorResponse("Unknown status: " + statusParam));
                    return;
                }
            }

            List<WorkflowExecution> executions = backend.listExecutions(status, limit, offset);
            ctx.json(executions);
        });

        // GET /executions/{id} — get by id
        app.get("/executions/{id}", ctx -> {
            String idStr = ctx.pathParam("id");
            ExecutionId executionId = parseExecutionId(idStr);
            if (executionId == null) {
                ctx.status(400).json(new ErrorResponse("Invalid execution id: " + idStr));
                return;
            }

            Optional<WorkflowExecution> execution = backend.getExecution(executionId);
            if (execution.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("Execution not found: " + idStr));
                return;
            }

            ctx.json(execution.get());
        });

        // POST /executions/{id}/cancel — cancel an execution
        app.post("/executions/{id}/cancel", ctx -> {
            String idStr = ctx.pathParam("id");
            ExecutionId executionId = parseExecutionId(idStr);
            if (executionId == null) {
                ctx.status(400).json(new ErrorResponse("Invalid execution id: " + idStr));
                return;
            }

            Optional<WorkflowExecution> existing = backend.getExecution(executionId);
            if (existing.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("Execution not found: " + idStr));
                return;
            }

            backend.updateExecutionStatus(executionId, WorkflowStatus.CANCELLED);
            ctx.json(backend.getExecution(executionId).get());
        });

        // GET /executions/{id}/events — get event log
        app.get("/executions/{id}/events", ctx -> {
            String idStr = ctx.pathParam("id");
            ExecutionId executionId = parseExecutionId(idStr);
            if (executionId == null) {
                ctx.status(400).json(new ErrorResponse("Invalid execution id: " + idStr));
                return;
            }

            Optional<WorkflowExecution> existing = backend.getExecution(executionId);
            if (existing.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("Execution not found: " + idStr));
                return;
            }

            ctx.json(backend.getEvents(executionId));
        });
    }

    private static ExecutionId parseExecutionId(String raw) {
        try {
            // Handle both "exec_<hex32>" and plain UUID forms
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

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

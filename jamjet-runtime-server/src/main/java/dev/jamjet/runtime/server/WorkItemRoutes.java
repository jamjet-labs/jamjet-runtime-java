package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.ExecutionId;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.WorkItem;
import dev.jamjet.runtime.server.dto.ClaimWorkItemRequest;
import dev.jamjet.runtime.server.dto.EnqueueWorkItemRequest;
import dev.jamjet.runtime.server.dto.FailWorkItemRequest;
import dev.jamjet.runtime.server.dto.HeartbeatRequest;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class WorkItemRoutes {

    public static void register(Javalin app, StateBackend backend) {

        // POST /work-items — enqueue a new work item
        app.post("/work-items", ctx -> {
            EnqueueWorkItemRequest req = ctx.bodyAsClass(EnqueueWorkItemRequest.class);
            if (req.executionId() == null || req.nodeId() == null || req.queueType() == null) {
                ctx.status(400).json(new ErrorResponse(
                        "Fields 'execution_id', 'node_id', and 'queue_type' are required"));
                return;
            }

            ExecutionId execId;
            try {
                execId = parseExecutionId(req.executionId());
            } catch (Exception e) {
                ctx.status(400).json(new ErrorResponse("Invalid execution_id: " + req.executionId()));
                return;
            }

            WorkItem item = new WorkItem(
                    UUID.randomUUID(),
                    execId,
                    req.nodeId(),
                    req.queueType(),
                    req.payload(),
                    1,
                    3,
                    Instant.now(),
                    null,
                    null
            );

            UUID id = backend.enqueueWorkItem(item);
            ctx.status(201).json(item);
        });

        // POST /work-items/claim — claim a work item
        app.post("/work-items/claim", ctx -> {
            ClaimWorkItemRequest req = ctx.bodyAsClass(ClaimWorkItemRequest.class);
            if (req.workerId() == null || req.queueTypes() == null || req.queueTypes().isEmpty()) {
                ctx.status(400).json(new ErrorResponse(
                        "Fields 'worker_id' and 'queue_types' are required"));
                return;
            }

            Optional<WorkItem> claimed = backend.claimWorkItem(req.workerId(), req.queueTypes());
            if (claimed.isEmpty()) {
                ctx.status(204);
                return;
            }

            ctx.json(claimed.get());
        });

        // POST /work-items/{id}/complete — complete a work item
        app.post("/work-items/{id}/complete", ctx -> {
            UUID itemId = parseUUID(ctx.pathParam("id"));
            if (itemId == null) {
                ctx.status(400).json(new ErrorResponse("Invalid work item id"));
                return;
            }

            backend.completeWorkItem(itemId);
            ctx.status(204);
        });

        // POST /work-items/{id}/fail — fail a work item
        app.post("/work-items/{id}/fail", ctx -> {
            UUID itemId = parseUUID(ctx.pathParam("id"));
            if (itemId == null) {
                ctx.status(400).json(new ErrorResponse("Invalid work item id"));
                return;
            }

            FailWorkItemRequest req = ctx.bodyAsClass(FailWorkItemRequest.class);
            String error = req.error() != null ? req.error() : "unknown error";

            backend.failWorkItem(itemId, error);
            ctx.status(204);
        });

        // POST /work-items/{id}/heartbeat — renew lease
        app.post("/work-items/{id}/heartbeat", ctx -> {
            UUID itemId = parseUUID(ctx.pathParam("id"));
            if (itemId == null) {
                ctx.status(400).json(new ErrorResponse("Invalid work item id"));
                return;
            }

            HeartbeatRequest req = ctx.bodyAsClass(HeartbeatRequest.class);
            if (req.workerId() == null) {
                ctx.status(400).json(new ErrorResponse("Field 'worker_id' is required"));
                return;
            }

            backend.renewLease(itemId, req.workerId());
            ctx.status(204);
        });
    }

    private static UUID parseUUID(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static ExecutionId parseExecutionId(String raw) {
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
    }
}

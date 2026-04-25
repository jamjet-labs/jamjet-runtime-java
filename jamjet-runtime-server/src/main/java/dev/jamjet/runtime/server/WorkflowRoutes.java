package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.WorkflowDefinition;
import dev.jamjet.runtime.server.dto.CreateWorkflowRequest;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class WorkflowRoutes {

    public static void register(Javalin app, StateBackend backend) {

        // POST /workflows — store a new workflow definition
        app.post("/workflows", ctx -> {
            CreateWorkflowRequest req = ctx.bodyAsClass(CreateWorkflowRequest.class);
            if (req.ir() == null) {
                ctx.status(400).json(new ErrorResponse("Field 'ir' is required"));
                return;
            }

            // Extract workflowId and version from IR, or generate defaults
            String workflowId = req.ir().has("workflow_id")
                    ? req.ir().get("workflow_id").asText()
                    : UUID.randomUUID().toString();
            String version = req.ir().has("version")
                    ? req.ir().get("version").asText()
                    : "1";

            WorkflowDefinition def = new WorkflowDefinition(
                    workflowId, version, req.ir(), Instant.now());

            backend.storeWorkflow(def);

            ctx.status(201).json(Map.of(
                    "workflow_id", workflowId,
                    "version", version
            ));
        });
    }
}

package dev.jamjet.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.StateBackendException;
import dev.jamjet.runtime.core.state.Tenant;
import dev.jamjet.runtime.core.state.TenantLimits;
import dev.jamjet.runtime.core.state.TenantStatus;
import dev.jamjet.runtime.server.dto.CreateTenantRequest;
import dev.jamjet.runtime.server.dto.UpdateTenantRequest;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class TenantRoutes {

    public static void register(Javalin app, StateBackend backend) {

        // POST /tenants — create a new tenant
        app.post("/tenants", ctx -> {
            CreateTenantRequest req = ctx.bodyAsClass(CreateTenantRequest.class);
            if (req.id() == null || req.id().isBlank()) {
                ctx.status(400).json(new ErrorResponse("Field 'id' is required"));
                return;
            }
            if (req.name() == null || req.name().isBlank()) {
                ctx.status(400).json(new ErrorResponse("Field 'name' is required"));
                return;
            }

            Instant now = Instant.now();
            Tenant tenant = new Tenant(req.id(), req.name(), TenantStatus.ACTIVE,
                    null, null, now, now);

            try {
                backend.createTenant(tenant);
            } catch (StateBackendException e) {
                if (e.getKind() == StateBackendException.Kind.DATABASE
                        && e.getMessage().contains("not supported")) {
                    ctx.status(501).json(new ErrorResponse("Tenant management not supported by this backend"));
                    return;
                }
                throw e;
            }

            ctx.status(201).json(tenant);
        });

        // GET /tenants — list all tenants
        app.get("/tenants", ctx -> {
            List<Tenant> tenants = backend.listTenants();
            ctx.json(tenants);
        });

        // GET /tenants/{id} — get by id
        app.get("/tenants/{id}", ctx -> {
            String id = ctx.pathParam("id");
            Optional<Tenant> tenant = backend.getTenant(id);
            if (tenant.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("Tenant not found: " + id));
                return;
            }
            ctx.json(tenant.get());
        });

        // PUT /tenants/{id} — update a tenant
        app.put("/tenants/{id}", ctx -> {
            String id = ctx.pathParam("id");

            Optional<Tenant> existing = backend.getTenant(id);
            if (existing.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("Tenant not found: " + id));
                return;
            }

            UpdateTenantRequest req = ctx.bodyAsClass(UpdateTenantRequest.class);
            Tenant current = existing.get();

            String newName = req.name() != null ? req.name() : current.name();
            TenantStatus newStatus = current.status();
            if (req.status() != null) {
                try {
                    newStatus = TenantStatus.fromValue(req.status());
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(new ErrorResponse("Unknown status: " + req.status()));
                    return;
                }
            }

            // Parse limits from JSON if provided
            TenantLimits newLimits = current.limits();
            if (req.limits() != null) {
                ObjectMapper mapper = JamjetJson.shared();
                newLimits = mapper.treeToValue(req.limits(), TenantLimits.class);
            }

            Tenant updated = new Tenant(
                    current.id(),
                    newName,
                    newStatus,
                    req.policy() != null ? req.policy() : current.policy(),
                    newLimits,
                    current.createdAt(),
                    Instant.now()
            );

            try {
                backend.updateTenant(updated);
            } catch (StateBackendException e) {
                if (e.getKind() == StateBackendException.Kind.DATABASE
                        && e.getMessage().contains("not supported")) {
                    ctx.status(501).json(new ErrorResponse("Tenant management not supported by this backend"));
                    return;
                }
                throw e;
            }

            ctx.json(updated);
        });
    }
}

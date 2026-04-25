package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.StateBackendException;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JamjetServer {

    private static final Logger log = LoggerFactory.getLogger(JamjetServer.class);

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromEnv();

        StateBackend backend = createBackend(config);

        Javalin app = createApp(backend, config);
        app.start(config.port());

        log.info("JamJet server started on port {}", config.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            app.stop();
        }));
    }

    public static Javalin createApp(StateBackend backend, ServerConfig config) {
        Javalin app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(JamjetJson.shared(), false));
            cfg.showJavalinBanner = false;
        });

        // Exception handlers
        app.exception(StateBackendException.class, (e, ctx) -> {
            int status = switch (e.getKind()) {
                case NOT_FOUND -> 404;
                case SEQUENCE_CONFLICT -> 409;
                case DATABASE, SERIALIZATION -> 500;
            };
            ctx.status(status).json(new ErrorResponse(e.getMessage()));
        });
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        });
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception for {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorResponse("Internal server error"));
        });

        // Register route groups
        HealthRoutes.register(app);
        WorkflowRoutes.register(app, backend);
        ExecutionRoutes.register(app, backend);
        WorkItemRoutes.register(app, backend);
        TenantRoutes.register(app, backend);

        return app;
    }

    private static StateBackend createBackend(ServerConfig config) {
        return switch (config.storageBackend()) {
            case "in-memory" -> new InMemoryStateBackend();
            default -> throw new IllegalArgumentException(
                    "Unknown storage backend: " + config.storageBackend());
        };
    }
}

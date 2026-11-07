package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.JamjetJson;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.StateBackend;
import dev.jamjet.runtime.core.state.StateBackendException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Optional;

public class JamjetServer {

    private static final Logger log = LoggerFactory.getLogger(JamjetServer.class);

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromEnv();

        StateBackend backend = createBackend(config);

        Javalin app = start(backend, config, null);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            app.stop();
        }));
    }

    /**
     * Builds and starts the server on {@code config.host()} (loopback unless set) and
     * {@code config.port()}. Logs a warning when the address is not loopback and requests
     * are not authenticated.
     *
     * @param authHook checks every request except {@code GET /health}; {@code null} uses
     *                 {@code config.apiToken()} as a bearer token when set, else no auth
     */
    public static Javalin start(StateBackend backend, ServerConfig config, AuthHook authHook) {
        AuthHook hook = authHook != null ? authHook : authHookFor(config);
        exposureWarning(config.host(), hook != null).ifPresent(log::warn);
        Javalin app = createApp(backend, config, hook);
        app.start(config.host(), config.port());
        log.info("JamJet server listening on {}:{}{}", config.host(), app.port(),
                hook != null ? " (auth on)" : "");
        return app;
    }

    /** The bearer-token hook when {@code config.apiToken()} is set, otherwise {@code null}. */
    public static AuthHook authHookFor(ServerConfig config) {
        return config.apiToken() != null ? AuthHook.bearerToken(config.apiToken()) : null;
    }

    /**
     * A warning when {@code host} is reachable from other machines and requests are not
     * authenticated: anyone who can reach it can start, cancel and read executions.
     */
    static Optional<String> exposureWarning(String host, boolean authEnabled) {
        if (authEnabled || isLoopback(host)) {
            return Optional.empty();
        }
        return Optional.of("JamJet server is bound to " + host + " with no authentication: anyone who can "
                + "reach this address can start, cancel and read executions. Set JAMJET_API_TOKEN, or bind "
                + ServerConfig.DEFAULT_HOST + ".");
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** The app with {@code config}'s bearer token, if any. Not started. */
    public static Javalin createApp(StateBackend backend, ServerConfig config) {
        return createApp(backend, config, null);
    }

    /**
     * The app with an auth check in front of every route except {@code GET /health}.
     * Not started.
     *
     * @param authHook the check; {@code null} uses {@code config.apiToken()} as a bearer
     *                 token when set, else no authentication
     */
    public static Javalin createApp(StateBackend backend, ServerConfig config, AuthHook authHook) {
        AuthHook hook = authHook != null ? authHook : authHookFor(config);
        Javalin app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(JamjetJson.shared(), false));
            cfg.showJavalinBanner = false;
        });

        if (hook != null) {
            app.before(ctx -> {
                if (isPublic(ctx) || allowed(hook, ctx)) {
                    return;
                }
                ctx.header("WWW-Authenticate", "Bearer");
                ctx.status(401).json(new ErrorResponse("missing or invalid Authorization: Bearer <token>"));
                ctx.skipRemainingHandlers();
            });
        }

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

    private static boolean isPublic(Context ctx) {
        return ctx.method() == HandlerType.GET && "/health".equals(ctx.path());
    }

    private static boolean allowed(AuthHook hook, Context ctx) {
        try {
            return hook.authorize(ctx);
        } catch (RuntimeException e) {
            log.warn("Auth hook failed for {} {}; refusing the request", ctx.method(), ctx.path(), e);
            return false;
        }
    }

    private static StateBackend createBackend(ServerConfig config) {
        return switch (config.storageBackend()) {
            case "in-memory" -> new InMemoryStateBackend();
            default -> throw new IllegalArgumentException(
                    "Unknown storage backend: " + config.storageBackend());
        };
    }
}

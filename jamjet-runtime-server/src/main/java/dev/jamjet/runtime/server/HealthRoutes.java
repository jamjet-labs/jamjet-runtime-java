package dev.jamjet.runtime.server;

import io.javalin.Javalin;

public class HealthRoutes {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    public static void register(Javalin app) {
        app.get("/health", ctx -> {
            ctx.json(new HealthResponse("ok", VERSION));
        });
    }
}

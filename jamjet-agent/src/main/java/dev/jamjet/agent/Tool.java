package dev.jamjet.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a JamJet agent tool — the Java authoring analog of the
 * Python {@code @jamjet.tool} decorator.
 *
 * <p>A {@code @Tool} method on a tool-holder object is discovered by
 * {@link dev.jamjet.agent.tools.ToolRegistry}, which derives an OpenAI-format
 * function schema from the method's name + parameters and offers it to the model
 * in the agent loop's Model nodes. When the model requests the tool, the durable
 * Java tool-worker (Phase B-3) resolves it back to this method by
 * <em>class name + method name</em> via the same registry and invokes it
 * reflectively — only declared {@code @Tool} methods are ever callable, never an
 * arbitrary class named in a payload.
 *
 * <p>This is a net-new authoring annotation, deliberately distinct from the
 * unrelated {@code dev.jamjet.cloud.agentboundary.Tool} (receipt metadata) and
 * {@code org.springframework.ai.tool.annotation.Tool} (Spring AI) types.
 *
 * <p>Usage:
 * <pre>{@code
 * class WeatherTools {
 *     @Tool(description = "Look up the current weather for a city.")
 *     public String getWeather(String city) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /**
     * The tool name offered to the model. Defaults to the method name when empty.
     * Set this when the idiomatic Java method name (camelCase) should be exposed
     * to the model under a different identifier (e.g. {@code "web_search"}).
     */
    String name() default "";

    /**
     * A human-readable description of what the tool does, surfaced to the model
     * in the function schema. Empty by default.
     */
    String description() default "";
}

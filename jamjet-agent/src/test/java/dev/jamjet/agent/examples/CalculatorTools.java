package dev.jamjet.agent.examples;

import dev.jamjet.agent.Tool;

/**
 * A tiny tool-holder for the {@link CalculatorAgentExample}: one {@code @Tool} method
 * the model can call to do exact arithmetic. Idiomatic Java — a plain class with a
 * {@code @Tool}-annotated method; the {@code Agent} builder derives the OpenAI function
 * schema from the method's name + typed parameters, and the durable
 * {@link dev.jamjet.agent.worker.JavaToolWorker} invokes it reflectively when the model
 * calls it.
 */
public final class CalculatorTools {

    /**
     * Evaluate a binary arithmetic operation. The {@code op} is one of
     * {@code add} / {@code subtract} / {@code multiply} / {@code divide}.
     *
     * <p>The JSON arguments the model sends ({@code {"a": 5, "b": 3, "op": "add"}}) are
     * coerced to these typed parameters by name (Jackson), so the method stays plain Java.
     */
    @Tool(name = "calculate", description = "Evaluate a binary arithmetic operation: add, subtract, multiply, or divide.")
    public String calculate(double a, double b, String op) {
        double result = switch (op) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> a / b;
            default -> throw new IllegalArgumentException("unknown op: " + op);
        };
        // Render whole-number results without a trailing ".0" for clean tool output.
        if (result == Math.rint(result) && !Double.isInfinite(result)) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }
}

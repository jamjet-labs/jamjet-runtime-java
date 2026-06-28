package dev.jamjet.agent.examples;

import dev.jamjet.agent.Tool;

import java.math.BigDecimal;
import java.math.MathContext;

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
        // Exact decimal arithmetic via BigDecimal: avoids binary-float noise (0.1 + 0.2)
        // and lets divide-by-zero be a clean error rather than producing "Infinity".
        // valueOf(double) uses the canonical short decimal string, so "0.1" stays "0.1".
        BigDecimal x = BigDecimal.valueOf(a);
        BigDecimal y = BigDecimal.valueOf(b);
        BigDecimal result;
        switch (op) {
            case "add" -> result = x.add(y);
            case "subtract" -> result = x.subtract(y);
            case "multiply" -> result = x.multiply(y);
            case "divide" -> {
                if (y.signum() == 0) {
                    return "ERROR: division by zero";
                }
                result = x.divide(y, MathContext.DECIMAL64);
            }
            default -> {
                return "ERROR: unknown op '" + op + "' (expected add, subtract, multiply, or divide)";
            }
        }
        // Render whole-number results without a trailing ".0"; otherwise strip trailing
        // zeros and render in plain (non-scientific) decimal notation.
        result = result.stripTrailingZeros();
        if (result.scale() <= 0) {
            return result.toBigInteger().toString();
        }
        return result.toPlainString();
    }
}

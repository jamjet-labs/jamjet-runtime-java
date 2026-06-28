package dev.jamjet.agent.spring.example;

import dev.jamjet.agent.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * A tool-holder authored as an ordinary Spring {@code @Component}: the
 * {@link dev.jamjet.agent.spring.ToolBeanRegistrar} scans its {@code @Tool} method into the
 * auto-configured {@link dev.jamjet.agent.tools.ToolRegistry}, so it needs no manual
 * registration. The same {@code @Tool} method the {@code Agent}'s IR offers the model is the
 * one the durable {@link dev.jamjet.agent.worker.JavaToolWorker} invokes when the model calls it.
 */
@Component
public class CalculatorTools {

    /** Evaluate a binary arithmetic operation: {@code add} / {@code subtract} / {@code multiply} / {@code divide}. */
    @Tool(name = "calculate", description = "Evaluate a binary arithmetic operation: add, subtract, multiply, or divide.")
    public String calculate(double a, double b, String op) {
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
        result = result.stripTrailingZeros();
        return result.scale() <= 0 ? result.toBigInteger().toString() : result.toPlainString();
    }
}

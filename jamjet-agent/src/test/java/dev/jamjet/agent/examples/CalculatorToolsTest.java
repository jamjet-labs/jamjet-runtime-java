package dev.jamjet.agent.examples;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the example {@link CalculatorTools}: exact decimal arithmetic via
 * BigDecimal, a clean error (not {@code Infinity}) on divide-by-zero, and whole-number
 * results rendered without a trailing {@code .0}.
 */
class CalculatorToolsTest {

    private final CalculatorTools calc = new CalculatorTools();

    @Test
    void wholeNumberResultsHaveNoTrailingDecimal() {
        assertThat(calc.calculate(5, 3, "add")).isEqualTo("8");
        assertThat(calc.calculate(5, 3, "subtract")).isEqualTo("2");
        assertThat(calc.calculate(5, 3, "multiply")).isEqualTo("15");
        assertThat(calc.calculate(10, 2, "divide")).isEqualTo("5");
    }

    @Test
    void decimalDivisionIsExactNotFloatingPointNoise() {
        assertThat(calc.calculate(10, 4, "divide")).isEqualTo("2.5");
        // 0.1 + 0.2 is 0.3 in decimal arithmetic (not 0.30000000000000004).
        assertThat(calc.calculate(0.1, 0.2, "add")).isEqualTo("0.3");
    }

    @Test
    void divisionByZeroReturnsCleanErrorNotInfinity() {
        String out = calc.calculate(5, 0, "divide");
        assertThat(out).doesNotContain("Infinity");
        assertThat(out.toLowerCase()).contains("zero");
    }

    @Test
    void unknownOperationReturnsCleanError() {
        String out = calc.calculate(1, 2, "modulo");
        assertThat(out.toLowerCase()).contains("modulo");
    }
}

package dev.jamjet.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation guards on {@link Budget}: besides the existing non-positive checks, an
 * entirely-empty budget (no tokens AND no cost) and a non-finite {@code costUsd}
 * (NaN / Infinity) are rejected — a NaN would otherwise slip past {@code costUsd <= 0}.
 */
class BudgetTest {

    @Test
    void rejectsEntirelyEmptyBudget() {
        assertThatThrownBy(() -> new Budget(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteCostUsd() {
        assertThatThrownBy(() -> new Budget(null, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(null, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(1_000, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stillRejectsNonPositiveValues() {
        assertThatThrownBy(() -> new Budget(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(null, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(-1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsValidBudgets() {
        assertThatCode(() -> new Budget(100_000, null)).doesNotThrowAnyException();
        assertThatCode(() -> new Budget(null, 2.5)).doesNotThrowAnyException();
        assertThatCode(() -> new Budget(100_000, 2.5)).doesNotThrowAnyException();
        assertThat(Budget.ofTokens(50_000).tokens()).isEqualTo(50_000);
        assertThat(Budget.ofCostUsd(0.5).costUsd()).isEqualTo(0.5);
    }
}

package dev.jamjet.agent;

/**
 * A per-run spending cap for a governed agent — the Java analog of the Python
 * {@code jamjet.agents.governance.Budget}. At least one field must be set (an entirely
 * empty budget is rejected); set either or both.
 *
 * <p>At compile time {@code costUsd} maps to the IR's {@code cost_budget_usd} and
 * {@code tokens} maps to {@code token_budget.total_tokens} (a combined input+output
 * cap); the Rust engine enforces both fail-closed. A {@code null} field is uncapped.
 *
 * @param tokens  total token cap (input + output combined), or {@code null} for uncapped
 * @param costUsd wall-cost cap in US dollars, or {@code null} for uncapped
 */
public record Budget(Integer tokens, Double costUsd) {

    public Budget {
        if (tokens == null && costUsd == null) {
            throw new IllegalArgumentException("Budget must cap tokens and/or cost (both were null)");
        }
        if (tokens != null && tokens <= 0) {
            throw new IllegalArgumentException("Budget.tokens must be positive");
        }
        if (costUsd != null) {
            // Guard non-finite first: NaN/Infinity slip past the `<= 0` check (every NaN
            // comparison is false), so they would otherwise be accepted as a "cap".
            if (!Double.isFinite(costUsd)) {
                throw new IllegalArgumentException("Budget.costUsd must be a finite number");
            }
            if (costUsd <= 0) {
                throw new IllegalArgumentException("Budget.costUsd must be positive");
            }
        }
    }

    /** A token-only budget. */
    public static Budget ofTokens(int tokens) {
        return new Budget(tokens, null);
    }

    /** A cost-only budget. */
    public static Budget ofCostUsd(double costUsd) {
        return new Budget(null, costUsd);
    }
}

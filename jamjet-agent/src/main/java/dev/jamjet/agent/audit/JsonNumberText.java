package dev.jamjet.agent.audit;

/**
 * A JSON number kept as the text it was written as. An entry's hash covers the runtime's
 * own JSON text of its event, and a number parsed into a {@code double} and printed again
 * can come back spelled differently ({@code 1e+16} and {@code 1.0E16}).
 */
public record JsonNumberText(String text) {
    @Override
    public String toString() {
        return text;
    }
}

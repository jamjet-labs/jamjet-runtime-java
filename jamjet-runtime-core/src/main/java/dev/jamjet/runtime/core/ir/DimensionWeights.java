package dev.jamjet.runtime.core.ir;

public record DimensionWeights(
        double skill,
        double cost,
        double latency,
        double trust
) {
    public static DimensionWeights defaults() {
        return new DimensionWeights(0.4, 0.2, 0.2, 0.2);
    }
}

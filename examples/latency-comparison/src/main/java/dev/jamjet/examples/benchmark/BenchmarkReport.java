package dev.jamjet.examples.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects latency samples (in nanoseconds) and computes percentile statistics.
 * Used to compare embedded runtime vs REST sidecar performance.
 */
public class BenchmarkReport {

    private final List<Long> nanoSamples = new ArrayList<>();

    public void addSample(long nanos) {
        nanoSamples.add(nanos);
    }

    public int sampleCount() {
        return nanoSamples.size();
    }

    /**
     * Computes a percentile of the collected samples.
     *
     * @param percentile value between 0 and 100 (e.g. 50, 95, 99)
     * @return the percentile in milliseconds
     */
    public double percentileMs(int percentile) {
        if (nanoSamples.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(nanoSamples);
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index) / 1_000_000.0;
    }

    /**
     * Prints a formatted two-column comparison table of embedded vs REST sidecar latency,
     * including overhead savings as a percentage.
     *
     * @param embedded the report for the embedded (new path) benchmark
     * @param rest     the report for the REST sidecar (old path) benchmark
     * @param title    optional label for the report section
     */
    public static void printComparison(BenchmarkReport embedded, BenchmarkReport rest, String title) {
        System.out.printf("%n%s (n=%d)%n", title, embedded.sampleCount());
        System.out.printf("%-28s %8s %8s %8s%n", "", "p50", "p95", "p99");
        System.out.printf("%-28s %7.2fms %7.2fms %7.2fms%n",
                "Embedded runtime",
                embedded.percentileMs(50),
                embedded.percentileMs(95),
                embedded.percentileMs(99));
        System.out.printf("%-28s %7.2fms %7.2fms %7.2fms%n",
                "REST sidecar",
                rest.percentileMs(50),
                rest.percentileMs(95),
                rest.percentileMs(99));

        double savedP50 = savingsPercent(rest.percentileMs(50), embedded.percentileMs(50));
        double savedP95 = savingsPercent(rest.percentileMs(95), embedded.percentileMs(95));
        double savedP99 = savingsPercent(rest.percentileMs(99), embedded.percentileMs(99));
        System.out.printf("%-28s %7.0f%% %7.0f%% %7.0f%%%n",
                "Overhead saved", savedP50, savedP95, savedP99);
    }

    private static double savingsPercent(double rest, double embedded) {
        if (rest <= 0) return 0;
        double saved = (rest - embedded) / rest * 100.0;
        return Math.max(0, saved);
    }
}

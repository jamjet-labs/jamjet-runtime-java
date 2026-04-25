package dev.jamjet.examples.benchmark;

/**
 * Entry point for the JamJet latency comparison benchmark.
 *
 * <p>Runs two phases:</p>
 * <ol>
 *   <li><b>Phase 1 – Single workflow latency</b>: 100 warmup + 1000 measured iterations
 *       on both the embedded path (in-process) and the REST sidecar path (WireMock stub).</li>
 *   <li><b>Phase 2 – Concurrent throughput</b>: 100 simultaneous 3-node workflows on both paths.</li>
 * </ol>
 *
 * <p>Run with: {@code mvn compile exec:java}</p>
 */
public class BenchmarkRunner {

    private static final int WARMUP = 100;
    private static final int ITERATIONS = 1000;
    private static final int CONCURRENCY = 100;

    public static void main(String[] args) throws Exception {
        printHeader();

        // ── Phase 1: Single workflow latency ──────────────────────────────
        System.out.println("Phase 1: Single Workflow Latency (" + ITERATIONS + " iterations)");
        System.out.println("  Warming up...");

        NewPathBenchmark embedded = new NewPathBenchmark();
        OldPathBenchmark rest = new OldPathBenchmark();
        rest.setup();

        try {
            BenchmarkReport embeddedReport = new BenchmarkReport();
            embedded.run(WARMUP, ITERATIONS).forEach(embeddedReport::addSample);

            BenchmarkReport restReport = new BenchmarkReport();
            rest.run(WARMUP, ITERATIONS).forEach(restReport::addSample);

            printPhase1Table(embeddedReport, restReport);

            // ── Phase 2: Concurrent throughput ────────────────────────────
            System.out.println("\nPhase 2: Concurrent Throughput (" + CONCURRENCY + " simultaneous workflows)");

            ConcurrencyBenchmark concurrency = new ConcurrencyBenchmark();
            ConcurrencyBenchmark.ConcurrencyResult embeddedConc = concurrency.runEmbedded(CONCURRENCY);
            ConcurrencyBenchmark.ConcurrencyResult restConc = concurrency.runRest(rest, CONCURRENCY);

            printPhase2Table(embeddedConc, restConc);

        } finally {
            rest.teardown();
        }

        System.out.println();
    }

    private static void printHeader() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     JamJet Runtime Benchmark — 3-node workflow          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printPhase1Table(BenchmarkReport embedded, BenchmarkReport rest) {
        System.out.printf("%n%-28s %8s %8s %8s%n", "", "p50", "p95", "p99");
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

    private static void printPhase2Table(
            ConcurrencyBenchmark.ConcurrencyResult embedded,
            ConcurrencyBenchmark.ConcurrencyResult rest) {
        System.out.printf("Embedded:  %d/%d completed in %dms   (avg %.2fms/workflow)%n",
                embedded.completed(), embedded.total(), embedded.totalMs(), embedded.avgMs());
        System.out.printf("REST:      %d/%d completed in %dms   (avg %.2fms/workflow)%n",
                rest.completed(), rest.total(), rest.totalMs(), rest.avgMs());

        double speedup = rest.totalMs() > 0 ? (double) rest.totalMs() / embedded.totalMs() : 0;
        System.out.printf("Speedup:   %.1fx%n", speedup);
    }

    private static double savingsPercent(double restMs, double embeddedMs) {
        if (restMs <= 0) return 0;
        return Math.max(0, (restMs - embeddedMs) / restMs * 100.0);
    }
}

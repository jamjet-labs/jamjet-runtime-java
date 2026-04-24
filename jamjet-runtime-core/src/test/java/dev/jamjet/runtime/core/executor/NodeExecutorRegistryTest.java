package dev.jamjet.runtime.core.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeExecutorRegistryTest {

    @Test
    void registerAndLookup() {
        var registry = new NodeExecutorRegistry();
        var executor = new NoOpExecutor();
        registry.register("model", executor);

        var result = registry.get("model");
        assertTrue(result.isPresent());
        assertSame(executor, result.get());
    }

    @Test
    void lookupMissingReturnsEmpty() {
        var registry = new NodeExecutorRegistry();
        assertTrue(registry.get("unknown").isEmpty());
    }

    @Test
    void registerOverwrites() {
        var registry = new NodeExecutorRegistry();
        var first = new NoOpExecutor();
        var second = new NoOpExecutor();
        registry.register("tool", first);
        registry.register("tool", second);

        assertSame(second, registry.get("tool").orElseThrow());
    }

    @Test
    void registeredKinds() {
        var registry = new NodeExecutorRegistry();
        registry.register("model", new NoOpExecutor());
        registry.register("tool", new NoOpExecutor());

        var kinds = registry.registeredKinds();
        assertEquals(2, kinds.size());
        assertTrue(kinds.contains("model"));
        assertTrue(kinds.contains("tool"));
    }

    @Test
    void noOpExecutorReturnsEmptyResult() throws NodeExecutionException {
        var executor = new NoOpExecutor();
        var result = executor.execute(null);

        assertNotNull(result);
        assertEquals(0L, result.durationMs());
        assertNotNull(result.output());
        assertNotNull(result.statePatch());
        assertNull(result.genAiSystem());
        assertNull(result.genAiModel());
        assertNull(result.inputTokens());
        assertNull(result.outputTokens());
        assertNull(result.finishReason());
        assertNull(result.costUsd());
        assertNull(result.provenance());
    }
}

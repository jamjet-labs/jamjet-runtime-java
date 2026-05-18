package dev.jamjet.cloud.agentboundary;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LoggingActionReceiptEmitter. Uses Logback's programmatic ListAppender
 * to capture log events without a test framework extension.
 */
class LoggingActionReceiptEmitterTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger emitterLogger;

    @BeforeEach
    void setUp() {
        emitterLogger = (Logger) LoggerFactory.getLogger(LoggingActionReceiptEmitter.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        emitterLogger.addAppender(listAppender);
        emitterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        emitterLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    private ActionReceipt sampleReceipt() {
        return new ActionReceipt(
            "agentboundary/v0.1",
            "11111111-2222-4333-8444-555555555555",
            "2026-06-15T12:00:00Z",
            new Actor(ActorType.HUMAN, "u_alice", null),
            new Agent("jamjet", "0.8.5", "claude-opus-4-7", null),
            new Tool("github-mcp", null, "github.merge"),
            new Target("github.com/jamjet-labs/agentboundary", Environment.PROD, null),
            "a".repeat(64),
            new Policy("prod-merges-require-approval", "1", PolicyDecision.ALLOW),
            null,       // no approval needed for allow decision
            new Execution(ExecutionStatus.SUCCESS, "2026-06-15T12:00:01Z", null, null),
            "b".repeat(64)
        );
    }

    @Test
    void emitLogsAtInfoLevel() {
        LoggingActionReceiptEmitter emitter = new LoggingActionReceiptEmitter();
        emitter.emit(sampleReceipt());

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void emitUsesReceiptMarker() {
        LoggingActionReceiptEmitter emitter = new LoggingActionReceiptEmitter();
        emitter.emit(sampleReceipt());

        ILoggingEvent event = listAppender.list.get(0);
        Marker marker = event.getMarker();
        assertThat(marker).isNotNull();
        assertThat(marker.getName()).isEqualTo(LoggingActionReceiptEmitter.RECEIPT_MARKER_NAME);
    }

    @Test
    void emitIncludesReceiptIdInJson() {
        LoggingActionReceiptEmitter emitter = new LoggingActionReceiptEmitter();
        emitter.emit(sampleReceipt());

        String message = listAppender.list.get(0).getFormattedMessage();
        assertThat(message).contains("11111111-2222-4333-8444-555555555555");
    }

    @Test
    void emitIncludesVersionInJson() {
        LoggingActionReceiptEmitter emitter = new LoggingActionReceiptEmitter();
        emitter.emit(sampleReceipt());

        String message = listAppender.list.get(0).getFormattedMessage();
        assertThat(message).contains("agentboundary/v0.1");
    }

    @Test
    void emitIncludesPolicyDecisionInJson() {
        LoggingActionReceiptEmitter emitter = new LoggingActionReceiptEmitter();
        emitter.emit(sampleReceipt());

        String message = listAppender.list.get(0).getFormattedMessage();
        assertThat(message).contains("allow");
    }

    @Test
    void markerNameConstantMatches() {
        assertThat(LoggingActionReceiptEmitter.RECEIPT_MARKER_NAME)
            .isEqualTo("agentboundary.receipt");
    }

    @Test
    void staticMarkerAccessorReturnsCorrectMarker() {
        Marker marker = LoggingActionReceiptEmitter.receiptMarker();
        assertThat(marker.getName()).isEqualTo("agentboundary.receipt");
    }
}

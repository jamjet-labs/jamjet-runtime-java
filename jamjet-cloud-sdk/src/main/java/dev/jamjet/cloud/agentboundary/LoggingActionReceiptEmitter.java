package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Default {@link ActionReceiptEmitter}: serializes the receipt to JSON via Jackson and
 * logs at SLF4J INFO with the marker {@code agentboundary.receipt}. Suitable for
 * development, local debugging, and pipeline integrations where receipts flow to a
 * log aggregator (Splunk, Datadog Logs, Loki, Elastic, etc.).
 *
 * <p>Thread-safe. The underlying {@link ObjectMapper} is shared across calls but Jackson's
 * {@code ObjectMapper} is thread-safe for reading/writing once fully configured.
 */
public class LoggingActionReceiptEmitter implements ActionReceiptEmitter {

    /** SLF4J marker name used on every receipt log line. Consumers can filter on this. */
    public static final String RECEIPT_MARKER_NAME = "agentboundary.receipt";

    private static final Logger log = LoggerFactory.getLogger(LoggingActionReceiptEmitter.class);
    private static final Marker RECEIPT_MARKER = MarkerFactory.getMarker(RECEIPT_MARKER_NAME);

    private final ObjectMapper mapper;

    /** Construct a {@code LoggingActionReceiptEmitter} using a default {@link ObjectMapper}. */
    public LoggingActionReceiptEmitter() {
        this(new ObjectMapper());
    }

    /**
     * Construct a {@code LoggingActionReceiptEmitter} using the supplied {@link ObjectMapper}.
     * Use this constructor to share the application's configured mapper (e.g., one that has
     * JavaTimeModule registered, custom serializers, etc.).
     *
     * @param mapper the Jackson {@link ObjectMapper} to use for receipt serialization
     */
    public LoggingActionReceiptEmitter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @return the marker used on every log line emitted by this class */
    public static Marker receiptMarker() {
        return RECEIPT_MARKER;
    }

    @Override
    public void emit(ActionReceipt receipt) {
        try {
            String json = mapper.writeValueAsString(receipt);
            log.info(RECEIPT_MARKER, "{}", json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AgentBoundary receipt {}: {}",
                      receipt.receiptId(), e.getMessage());
        }
    }
}

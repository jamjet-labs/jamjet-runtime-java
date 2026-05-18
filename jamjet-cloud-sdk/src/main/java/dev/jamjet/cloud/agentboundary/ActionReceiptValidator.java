package dev.jamjet.cloud.agentboundary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates AgentBoundary v0.1 Action Receipts against the bundled JSON Schema.
 *
 * <p>Construction loads the schema once from the classpath resource
 * {@code agentboundary/action-receipt-v0.1.json}. Instances are reusable and thread-safe.
 *
 * <p>Use {@link #validate(Map)} for receipts represented as a Map (e.g., parsed from
 * raw JSON without binding to POJOs). Use {@link #validate(ActionReceipt)} for typed
 * POJO instances — the POJO is serialized via Jackson before validation.
 *
 * <p>The returned error list is sorted by JSON path depth then alphabetical path,
 * mirroring the Python reference implementation in
 * {@code agentboundary/src/agentboundary/validator.py}.
 */
public class ActionReceiptValidator {

    private static final String SCHEMA_RESOURCE = "agentboundary/action-receipt-v0.1.json";

    private final JsonSchema schema;
    private final ObjectMapper mapper;

    /** Construct a validator, loading the bundled JSON Schema from the classpath. */
    public ActionReceiptValidator() {
        this.mapper = new ObjectMapper();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "AgentBoundary schema resource not found on classpath: " + SCHEMA_RESOURCE);
            }
            JsonNode schemaNode = mapper.readTree(in);
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            // Enable format assertions (uuid, date-time) so they are hard errors, not annotations.
            config.setFormatAssertionsEnabled(true);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            this.schema = factory.getSchema(schemaNode, config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load AgentBoundary JSON Schema", e);
        }
    }

    /**
     * Validate a receipt represented as a {@code Map<String, Object>}.
     *
     * @param receipt the receipt to validate (keys must match the v0.1 schema)
     * @return an empty list if the receipt is valid; otherwise a sorted list of
     *         human-readable error messages, sorted by JSON path depth then path string
     */
    public List<String> validate(Map<String, Object> receipt) {
        JsonNode node = mapper.valueToTree(receipt);
        return runValidation(node);
    }

    /**
     * Validate a typed {@link ActionReceipt} POJO. Serializes via Jackson before
     * delegating to the JSON Schema validator.
     *
     * @param receipt the receipt to validate
     * @return an empty list if the receipt is valid; otherwise a sorted list of
     *         human-readable error messages
     */
    public List<String> validate(ActionReceipt receipt) {
        JsonNode node = mapper.valueToTree(receipt);
        return runValidation(node);
    }

    private List<String> runValidation(JsonNode node) {
        Set<ValidationMessage> errors = schema.validate(node);
        return errors.stream()
            .sorted(Comparator
                .comparingInt((ValidationMessage e) -> e.getInstanceLocation().getNameCount())
                .thenComparing(e -> e.getInstanceLocation().toString()))
            .map(this::formatError)
            .collect(Collectors.toList());
    }

    private String formatError(ValidationMessage err) {
        String path = err.getInstanceLocation().toString();
        // The root location is typically "$" or empty — normalize to "root" for readability.
        if (path.isEmpty() || "$".equals(path)) {
            path = "root";
        }
        return path + ": " + err.getMessage();
    }
}

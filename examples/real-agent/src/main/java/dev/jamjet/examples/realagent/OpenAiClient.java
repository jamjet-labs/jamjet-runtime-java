package dev.jamjet.examples.realagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jamjet.runtime.core.JamjetJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal OpenAI chat-completions client using JDK {@link HttpClient}.
 *
 * <p>Supports plain text responses and structured JSON responses via
 * {@code response_format: {type: "json_object"}}.</p>
 */
public class OpenAiClient {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;

    public OpenAiClient(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = JamjetJson.shared();
    }

    /**
     * Sends a chat message and returns the assistant's response content as a plain string.
     *
     * @param systemPrompt the system prompt
     * @param userMessage  the user message
     * @return the content field of the first choice
     */
    public String chat(String systemPrompt, String userMessage) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("model", DEFAULT_MODEL);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userMessage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = json.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    /**
     * Sends a chat message requesting a JSON response and deserializes it into {@code type}.
     *
     * <p>The system prompt is amended to instruct the model to respond with valid JSON only.
     * The response is parsed with Jackson using the shared {@link JamjetJson} ObjectMapper.</p>
     *
     * @param systemPrompt the system prompt
     * @param userMessage  the user message
     * @param type         the target type to deserialize the JSON response into
     * @return a deserialized instance of {@code T}
     */
    public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type)
            throws IOException, InterruptedException {
        String amendedSystem = systemPrompt +
                "\n\nIMPORTANT: Respond ONLY with valid JSON that matches the requested structure. No prose, no markdown.";

        ObjectNode body = json.createObjectNode();
        body.put("model", DEFAULT_MODEL);
        body.putObject("response_format").put("type", "json_object");

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", amendedSystem);
        messages.addObject().put("role", "user").put("content", userMessage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = json.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText();
        return json.readValue(content, type);
    }
}

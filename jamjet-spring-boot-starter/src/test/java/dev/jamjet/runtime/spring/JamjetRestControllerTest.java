package dev.jamjet.runtime.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import dev.jamjet.runtime.core.state.StateBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link JamjetRestController} using standalone MockMvc setup.
 */
class JamjetRestControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        StateBackend backend = new InMemoryStateBackend();
        JamjetRestController controller = new JamjetRestController(backend);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void health_returnsOk() throws Exception {
        mockMvc.perform(get("/jamjet/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"));
    }

    @Test
    void startExecution_withValidRequest_returnsCreated() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("workflowId", "my-workflow", "workflowVersion", "1")
        );

        mockMvc.perform(post("/jamjet/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId").value("my-workflow"))
                .andExpect(jsonPath("$.workflowVersion").value("1"))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void startExecution_withMissingWorkflowId_returnsBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("workflowVersion", "1")
        );

        mockMvc.perform(post("/jamjet/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listExecutions_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/jamjet/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getExecution_withUnknownId_returnsNotFound() throws Exception {
        mockMvc.perform(get("/jamjet/executions/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void cancelExecution_withUnknownId_returnsNotFound() throws Exception {
        mockMvc.perform(post("/jamjet/executions/00000000-0000-0000-0000-000000000001/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getEvents_withUnknownId_returnsNotFound() throws Exception {
        mockMvc.perform(get("/jamjet/executions/00000000-0000-0000-0000-000000000001/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startAndGetExecution_roundTrip() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("workflowId", "round-trip-wf", "workflowVersion", "2")
        );

        // Start
        String responseJson = mockMvc.perform(post("/jamjet/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Extract the executionId value (a UUID string)
        var response = objectMapper.readTree(responseJson);
        String execIdValue = response.get("executionId").asText();

        // Get it back
        mockMvc.perform(get("/jamjet/executions/" + execIdValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("round-trip-wf"));
    }
}

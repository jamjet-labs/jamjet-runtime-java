package dev.jamjet.runtime.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStatusTest {

    private final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void serialization() throws JsonProcessingException {
        assertThat(mapper.writeValueAsString(WorkflowStatus.PENDING)).isEqualTo("\"pending\"");
        assertThat(mapper.writeValueAsString(WorkflowStatus.RUNNING)).isEqualTo("\"running\"");
        assertThat(mapper.writeValueAsString(WorkflowStatus.PAUSED)).isEqualTo("\"paused\"");
        assertThat(mapper.writeValueAsString(WorkflowStatus.COMPLETED)).isEqualTo("\"completed\"");
        assertThat(mapper.writeValueAsString(WorkflowStatus.FAILED)).isEqualTo("\"failed\"");
        assertThat(mapper.writeValueAsString(WorkflowStatus.CANCELLED)).isEqualTo("\"cancelled\"");
        assertThat(mapper.writeValueAsString(WorkflowStatus.LIMIT_EXCEEDED)).isEqualTo("\"limit_exceeded\"");
    }

    @Test
    void deserialization() throws JsonProcessingException {
        assertThat(mapper.readValue("\"pending\"", WorkflowStatus.class)).isEqualTo(WorkflowStatus.PENDING);
        assertThat(mapper.readValue("\"running\"", WorkflowStatus.class)).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(mapper.readValue("\"limit_exceeded\"", WorkflowStatus.class)).isEqualTo(WorkflowStatus.LIMIT_EXCEEDED);
    }

    @Test
    void terminalStatuses() {
        assertThat(WorkflowStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(WorkflowStatus.FAILED.isTerminal()).isTrue();
        assertThat(WorkflowStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(WorkflowStatus.LIMIT_EXCEEDED.isTerminal()).isTrue();
    }

    @Test
    void activeStatuses() {
        assertThat(WorkflowStatus.PENDING.isActive()).isTrue();
        assertThat(WorkflowStatus.RUNNING.isActive()).isTrue();
        assertThat(WorkflowStatus.PAUSED.isActive()).isTrue();
        assertThat(WorkflowStatus.COMPLETED.isActive()).isFalse();
        assertThat(WorkflowStatus.FAILED.isActive()).isFalse();
    }

    @Test
    void validTransitions() {
        assertThat(WorkflowStatus.PENDING.canTransitionTo(WorkflowStatus.RUNNING)).isTrue();
        assertThat(WorkflowStatus.RUNNING.canTransitionTo(WorkflowStatus.PAUSED)).isTrue();
        assertThat(WorkflowStatus.RUNNING.canTransitionTo(WorkflowStatus.COMPLETED)).isTrue();
        assertThat(WorkflowStatus.RUNNING.canTransitionTo(WorkflowStatus.FAILED)).isTrue();
        assertThat(WorkflowStatus.RUNNING.canTransitionTo(WorkflowStatus.CANCELLED)).isTrue();
        assertThat(WorkflowStatus.RUNNING.canTransitionTo(WorkflowStatus.LIMIT_EXCEEDED)).isTrue();
        assertThat(WorkflowStatus.PAUSED.canTransitionTo(WorkflowStatus.RUNNING)).isTrue();
        assertThat(WorkflowStatus.PAUSED.canTransitionTo(WorkflowStatus.CANCELLED)).isTrue();
    }

    @Test
    void invalidTransitions() {
        assertThat(WorkflowStatus.PENDING.canTransitionTo(WorkflowStatus.COMPLETED)).isFalse();
        assertThat(WorkflowStatus.PENDING.canTransitionTo(WorkflowStatus.PAUSED)).isFalse();
        assertThat(WorkflowStatus.COMPLETED.canTransitionTo(WorkflowStatus.RUNNING)).isFalse();
        assertThat(WorkflowStatus.FAILED.canTransitionTo(WorkflowStatus.RUNNING)).isFalse();
        assertThat(WorkflowStatus.CANCELLED.canTransitionTo(WorkflowStatus.RUNNING)).isFalse();
        assertThat(WorkflowStatus.LIMIT_EXCEEDED.canTransitionTo(WorkflowStatus.RUNNING)).isFalse();
    }
}

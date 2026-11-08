package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.runtime.core.JamjetJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code approval_timeout_secs} and {@code escalate_after_secs} (Rust
 * {@code runtime/ir/src/workflow.rs}) survive a Java round trip, and a policy that does not
 * set them serializes without them, so its definition hash does not change.
 */
class PolicySetIrDeadlineTest {

    private final ObjectMapper mapper = JamjetJson.shared();

    @Test
    void deadlinesSurviveARoundTrip() throws Exception {
        String json = "{\"blocked_tools\":[],\"require_approval_for\":[\"payments.*\"],\"model_allowlist\":[],"
                + "\"approval_timeout_secs\":3600,\"escalate_after_secs\":600}";
        PolicySetIr policy = mapper.readValue(json, PolicySetIr.class);
        JsonNode back = mapper.valueToTree(policy);

        assertThat(back.path("approval_timeout_secs").asLong()).isEqualTo(3600L);
        assertThat(back.path("escalate_after_secs").asLong()).isEqualTo(600L);
    }

    @Test
    void anUnsetDeadlineIsNotWritten() throws Exception {
        PolicySetIr policy = mapper.readValue(
                "{\"blocked_tools\":[\"rm\"],\"require_approval_for\":[],\"model_allowlist\":[]}", PolicySetIr.class);
        JsonNode back = mapper.valueToTree(policy);

        assertThat(back.has("approval_timeout_secs")).isFalse();
        assertThat(back.has("escalate_after_secs")).isFalse();
    }

    @Test
    void deadlinesTheEngineWouldRefuseAreRefusedHere() {
        assertThatThrownBy(() -> PolicySetIr.empty().withApprovalDeadline(0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySetIr.empty().withApprovalDeadline(PolicySetIr.MAX_APPROVAL_TIMEOUT_SECS + 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySetIr.empty().withApprovalDeadline(60, 60L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySetIr.empty().withApprovalDeadline(60, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicySetIr(List.of(), List.of(), List.of(), null, 30L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs approval_timeout_secs");
        assertThatThrownBy(() -> mapper.readValue("{\"approval_timeout_secs\":0}", PolicySetIr.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        PolicySetIr ok = PolicySetIr.empty().withApprovalDeadline(PolicySetIr.MAX_APPROVAL_TIMEOUT_SECS,
                PolicySetIr.MAX_APPROVAL_TIMEOUT_SECS - 1);
        assertThat(ok.escalateAfterSecs()).isEqualTo(PolicySetIr.MAX_APPROVAL_TIMEOUT_SECS - 1);
    }
}

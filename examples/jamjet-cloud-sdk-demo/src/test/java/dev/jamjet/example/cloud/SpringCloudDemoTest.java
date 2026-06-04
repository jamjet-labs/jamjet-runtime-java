package dev.jamjet.example.cloud;

import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(StubChatModelConfig.class)
class SpringCloudDemoTest {

    @Autowired
    ChatClient chatClient;

    @Autowired
    ActionReceiptEmitter emitter;

    @Test
    void contextWiresAGovernedChatClient() {
        assertThat(chatClient).isNotNull();
        assertThat(emitter).isInstanceOf(FileActionReceiptEmitter.class);
    }

    @Test
    void aGovernedCallSucceedsThroughTheAdvisorChain() {
        String reply = chatClient.prompt().user("status of A-100?").call().content();
        assertThat(reply).contains("A-100");
    }
}

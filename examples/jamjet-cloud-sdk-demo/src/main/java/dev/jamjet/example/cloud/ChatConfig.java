package dev.jamjet.example.cloud;

import dev.jamjet.cloud.spring.ActionReceiptAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel, ActionReceiptAdvisor advisor, LookupTool lookupTool) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(advisor)
                .defaultTools(lookupTool)
                .build();
    }
}

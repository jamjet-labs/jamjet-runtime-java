package dev.jamjet.example.cloud;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
public class CloudDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudDemoApplication.class, args);
    }

    /** Live run only (not under test): make one governed call. */
    @Bean
    @Profile("!test")
    CommandLineRunner demo(ChatClient chatClient) {
        return args -> {
            String reply = chatClient.prompt()
                    .user("What is the status of order A-100? Use the orderStatus tool.")
                    .call()
                    .content();
            System.out.println("Agent reply: " + reply);
            System.out.println("A span was sent to JamJet Cloud; a receipt was written to ~/.jamjet/audit/cloud-sdk-demo.jsonl");
        };
    }
}

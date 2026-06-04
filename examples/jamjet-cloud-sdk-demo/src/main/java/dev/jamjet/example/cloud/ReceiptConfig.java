package dev.jamjet.example.cloud;

import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;
import dev.jamjet.cloud.spring.ActionReceiptAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

@Configuration
public class ReceiptConfig {

    @Bean
    public ActionReceiptEmitter actionReceiptEmitter() {
        Path file = Path.of(System.getProperty("user.home"), ".jamjet", "audit", "cloud-sdk-demo.jsonl");
        return new FileActionReceiptEmitter(file);
    }

    @Bean
    public ActionReceiptAdvisor actionReceiptAdvisor(Environment env, ActionReceiptEmitter emitter) {
        return new ActionReceiptAdvisor(env, emitter, Ordered.LOWEST_PRECEDENCE);
    }
}

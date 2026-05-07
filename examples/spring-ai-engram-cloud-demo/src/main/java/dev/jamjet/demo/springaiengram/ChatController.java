package dev.jamjet.demo.springaiengram;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final MemoryAgent agent;

    public ChatController(MemoryAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestParam String session, @RequestBody String message) {
        String reply = agent.chat(session, message);
        return new ChatResponse(session, reply);
    }

    public record ChatResponse(String session, String reply) {}
}

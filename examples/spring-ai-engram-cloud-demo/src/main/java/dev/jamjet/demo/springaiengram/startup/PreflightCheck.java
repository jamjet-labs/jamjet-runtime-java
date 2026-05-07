package dev.jamjet.demo.springaiengram.startup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
@Profile("!test")
public class PreflightCheck implements ApplicationRunner {

    private final Map<String, String> env;
    private final String engramHealthUrl;

    public PreflightCheck(@Value("${app.engram.health-url:http://127.0.0.1:9090/health}") String engramHealthUrl) {
        this(System.getenv(), engramHealthUrl);
    }

    // Test constructor.
    public PreflightCheck(Map<String, String> env, String engramHealthUrl) {
        this.env = env;
        this.engramHealthUrl = engramHealthUrl;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateEnv();
        waitForEngram();
    }

    public void validateEnv() {
        if (isBlank(env.get("OPENAI_API_KEY"))) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not set. Get a key at https://platform.openai.com/api-keys " +
                    "and put it in your .env file.");
        }
        if (isBlank(env.get("JAMJET_API_KEY"))) {
            throw new IllegalStateException(
                    "JAMJET_API_KEY is not set. Sign up at https://cloud.jamjet.dev, create a project, " +
                    "and put the API key in your .env file.");
        }
    }

    void waitForEngram() {
        // Implementation in Task 5.
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

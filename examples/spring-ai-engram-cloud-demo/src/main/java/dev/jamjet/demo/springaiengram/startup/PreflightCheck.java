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

    public void waitForEngram() {
        waitForEngramWithTimeout(Duration.ofSeconds(30));
    }

    public void waitForEngramWithTimeout(Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(engramHealthUrl))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        long deadlineMs = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // retry until deadline
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Engram", e);
            }
        }

        throw new IllegalStateException(
                "Engram is not reachable at " + engramHealthUrl + ". " +
                "Run `docker compose up -d` first.");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

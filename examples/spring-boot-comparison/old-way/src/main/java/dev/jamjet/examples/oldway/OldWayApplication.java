package dev.jamjet.examples.oldway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Old-way application: durable execution is delegated to a Rust sidecar over REST.
 * Requires the sidecar to be running at http://localhost:7474 (see README).
 */
@SpringBootApplication
public class OldWayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OldWayApplication.class, args);
    }
}

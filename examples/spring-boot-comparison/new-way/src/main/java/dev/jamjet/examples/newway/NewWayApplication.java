package dev.jamjet.examples.newway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * New-way application: JamJet runtime runs embedded inside the Spring Boot process.
 * No sidecar, no Docker, no REST calls between components — just one dependency and one YAML line.
 */
@SpringBootApplication
public class NewWayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewWayApplication.class, args);
    }
}

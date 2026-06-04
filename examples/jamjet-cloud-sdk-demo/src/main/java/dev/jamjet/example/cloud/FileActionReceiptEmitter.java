package dev.jamjet.example.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jamjet.cloud.agentboundary.ActionReceipt;
import dev.jamjet.cloud.agentboundary.ActionReceiptEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends each Action Receipt as one JSON line to a file (JSONL), mirroring the
 * {@code ~/.jamjet/audit/} pattern used by the TypeScript/Python SDKs. The Java
 * Cloud SDK does not yet ship a cloud-posting emitter, so this makes the audit
 * artifact tangible and inspectable.
 */
public final class FileActionReceiptEmitter implements ActionReceiptEmitter {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Path file;
    private final Object lock = new Object();

    public FileActionReceiptEmitter(Path file) {
        this.file = file;
    }

    @Override
    public void emit(ActionReceipt receipt) {
        try {
            String line = MAPPER.writeValueAsString(receipt) + System.lineSeparator();
            synchronized (lock) {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write receipt to " + file, e);
        }
    }
}

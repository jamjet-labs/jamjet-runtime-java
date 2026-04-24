package dev.jamjet.runtime.core.ir;

public record EdgeDef(
        String from,
        String to,
        String condition
) {}

package dev.jamjet.runtime.core.ir;

public record ConditionalBranch(
        String condition,
        String target
) {}

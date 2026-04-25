package dev.jamjet.examples.realagent;

import java.util.List;

/**
 * Structured document summary produced by the final summarize step.
 */
public record Summary(
        String title,
        List<String> keyPoints,
        List<String> topics,
        String sentiment,
        int wordCount
) {}

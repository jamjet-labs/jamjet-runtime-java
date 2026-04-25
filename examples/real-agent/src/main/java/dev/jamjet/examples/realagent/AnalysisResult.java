package dev.jamjet.examples.realagent;

import java.util.List;

/**
 * Raw analysis result returned by GPT-4o-mini after reading the document.
 */
public record AnalysisResult(
        List<String> keyPoints,
        List<String> topics,
        String sentiment
) {}

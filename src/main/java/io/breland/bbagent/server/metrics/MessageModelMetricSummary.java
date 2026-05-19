package io.breland.bbagent.server.metrics;

public record MessageModelMetricSummary(
    String modelKey,
    String modelLabel,
    String responsesModel,
    boolean premium,
    long messageCount,
    long activeUsers) {}

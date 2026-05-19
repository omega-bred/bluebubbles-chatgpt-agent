package io.breland.bbagent.server.metrics;

public record MessageModelMetricSummary(
    String modelKey,
    String modelLabel,
    String responsesModel,
    String plan,
    boolean premium,
    long messageCount,
    long activeUsers) {}

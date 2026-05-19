package io.breland.bbagent.server.metrics;

public record ToolAccountTypeMetricSummary(
    String accountType,
    String plan,
    boolean premium,
    long callCount,
    long successfulCalls,
    long failedCalls,
    long activeUsers) {}

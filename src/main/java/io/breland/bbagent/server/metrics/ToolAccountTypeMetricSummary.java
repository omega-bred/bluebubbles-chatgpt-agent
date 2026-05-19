package io.breland.bbagent.server.metrics;

public record ToolAccountTypeMetricSummary(
    boolean premium, long callCount, long successfulCalls, long failedCalls, long activeUsers) {}

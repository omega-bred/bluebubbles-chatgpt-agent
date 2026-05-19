package io.breland.bbagent.server.metrics;

public record ToolMetricTotals(
    long toolCallCount, long successfulToolCalls, long failedToolCalls, long activeUsers) {}

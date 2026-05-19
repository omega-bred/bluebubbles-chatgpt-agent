package io.breland.bbagent.server.metrics;

import java.time.Instant;

public record ToolMetricSummary(
    String toolName,
    String toolCategory,
    long callCount,
    long successfulCalls,
    long failedCalls,
    long activeUsers,
    double averageDurationMs,
    Instant lastUsedAt) {}

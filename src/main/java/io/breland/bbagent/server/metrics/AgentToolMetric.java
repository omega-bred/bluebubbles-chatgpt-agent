package io.breland.bbagent.server.metrics;

import java.time.Instant;

public record AgentToolMetric(
    String id,
    Instant occurredAt,
    String transport,
    String messageGuid,
    String chatGuidHash,
    String userKeyHash,
    String toolName,
    String toolCategory,
    boolean success,
    String failureType,
    long durationMillis,
    String modelKey,
    String modelLabel,
    String responsesModel,
    boolean premium,
    String workflowMode,
    Instant createdAt) {}

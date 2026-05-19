package io.breland.bbagent.server.metrics;

import java.time.Instant;

public record AgentMessageMetric(
    String id,
    Instant occurredAt,
    String transport,
    String messageGuid,
    String chatGuidHash,
    String userKeyHash,
    String modelKey,
    String modelLabel,
    String responsesModel,
    boolean premium,
    String workflowMode,
    Instant createdAt) {}

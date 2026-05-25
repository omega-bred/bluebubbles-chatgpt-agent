package io.breland.bbagent.server.metrics;

import io.breland.bbagent.server.agent.IncomingMessage;
import org.springframework.lang.Nullable;

public record AgentToolMetricEvent(
    IncomingMessage message,
    String toolName,
    String toolCategory,
    boolean success,
    @Nullable String failureType,
    long durationMillis) {}

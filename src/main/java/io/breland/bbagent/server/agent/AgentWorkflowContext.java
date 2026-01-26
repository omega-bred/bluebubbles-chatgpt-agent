package io.breland.bbagent.server.agent;

import java.time.Instant;

public record AgentWorkflowContext(
    String workflowId, String chatGuid, String messageGuid, long sequence, Instant startedAt) {}

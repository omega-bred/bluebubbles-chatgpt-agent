package io.breland.bbagent.server.agent.cadence.models;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.time.Instant;

public record CadenceMessageWorkflowRequest(
    AgentWorkflowContext workflowContext, IncomingMessage message, Instant scheduledFor) {}

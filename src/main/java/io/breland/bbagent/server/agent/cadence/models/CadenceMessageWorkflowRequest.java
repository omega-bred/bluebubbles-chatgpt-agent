package io.breland.bbagent.server.agent.cadence.models;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;

public record CadenceMessageWorkflowRequest(
    AgentWorkflowContext workflowContext, IncomingMessage message) {}

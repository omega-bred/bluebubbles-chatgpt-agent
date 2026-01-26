package io.breland.bbagent.server.agent;

public record CadenceMessageWorkflowRequest(
    AgentWorkflowContext workflowContext, IncomingMessage message) {}

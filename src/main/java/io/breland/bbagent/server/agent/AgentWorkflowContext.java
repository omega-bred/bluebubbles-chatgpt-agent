package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import java.time.Instant;

public record AgentWorkflowContext(
    String workflowId,
    String chatGuid,
    String messageGuid,
    Instant startedAt,
    DecisionRouting decisionRouting) {
  public AgentWorkflowContext(
      String workflowId, String chatGuid, String messageGuid, Instant startedAt) {
    this(workflowId, chatGuid, messageGuid, startedAt, DecisionRouting.EMPTY);
  }

  public AgentWorkflowContext {
    decisionRouting = decisionRouting == null ? DecisionRouting.EMPTY : decisionRouting;
  }

  public static String reactionWorkflowId(String chatGuid) {
    return chatGuid + ":reaction";
  }

  public boolean isReactionWorkflow() {
    return chatGuid != null && reactionWorkflowId(chatGuid).equals(workflowId);
  }
}

package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import java.time.Instant;

public record AgentWorkflowContext(
    String workflowId,
    String chatGuid,
    String messageGuid,
    Instant startedAt,
    DecisionRouting decisionRouting,
    Long responseVersion) {
  public AgentWorkflowContext(
      String workflowId,
      String chatGuid,
      String messageGuid,
      Instant startedAt,
      DecisionRouting decisionRouting) {
    this(workflowId, chatGuid, messageGuid, startedAt, decisionRouting, null);
  }

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
    return chatGuid != null
        && (responseVersion == null
            ? reactionWorkflowId(chatGuid).equals(workflowId)
            : (reactionWorkflowId(chatGuid) + ":")
                .concat(responseVersion.toString())
                .equals(workflowId));
  }
}
